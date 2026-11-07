# Trợ lý AI cá nhân offline cho Android

Ứng dụng trò chuyện bằng tiếng Việt (gõ hoặc nói), hiểu yêu cầu và thực hiện, ghi nhớ thông tin về bạn, và nhắc nhở đúng giờ. Mọi thứ chạy trên điện thoại, không cần mạng.

## Kiến trúc

```
 Bạn (gõ / nói)
     │  SpeechRecognizer (offline, vi-VN)
     ▼
 ChatViewModel ──► Assistant ("bộ não")
                     │ 1. lấy trí nhớ liên quan + lịch sử chat từ Room
                     │ 2. ghép prompt, gọi mô hình AI trên máy (MediaPipe)
                     │ 3. mô hình trả JSON: {reply, action}
                     ▼
                 ActionExecutor
                     ├─ remember  → lưu vào bảng Memory
                     ├─ remind    → bảng Reminder + AlarmManager → thông báo
                     ├─ alarm     → app Đồng hồ
                     ├─ call/sms  → mở màn hình gọi / soạn tin (bạn tự bấm)
                     └─ open_url  → trình duyệt, Google Maps…
     ▼
 Trả lời trên màn hình + đọc to bằng TextToSpeech
```

| Thành phần | File | Ghi chú |
|---|---|---|
| Mô hình AI | `llm/LlmEngine.kt` | Có giao diện `LlmEngine` để sau này thay bằng llama.cpp hoặc API online |
| Bộ não | `agent/Assistant.kt` | Prompt + đọc JSON trả về |
| Thực thi | `agent/ActionExecutor.kt` | Thêm hành động mới ở đây và trong prompt |
| Trí nhớ, nhắc nhở, lịch sử, sổ chi | `data/AppDb.kt` | Room + SQLCipher (mã hoá AES-256) |
| Khoản chi định kỳ | `finance/` | Sổ chi, nhắc 9h sáng, đọc biến động số dư |
| Nhận diện giọng | `voice/VoiceId.kt` | sherpa-onnx, offline |
| Tính cách, tự học | `learn/` | Hồ sơ, xưng hô, học ban đêm |
| Nhắc nhở | `reminder/Reminders.kt` | Tự đặt lại sau khi khởi động lại máy |
| Bảo mật | `security/` | Vân tay / PIN, nhận giọng, khoá mã hoá trong Keystore |
| Giao diện | `MainActivity.kt` | Jetpack Compose |

## Chọn mô hình AI theo RAM điện thoại

Máy của bạn (Galaxy S23 Ultra, Snapdragon 8 Gen 2, 8 hoặc 12 GB RAM): dùng **Gemma 3n E4B**, chạy trên GPU.

| RAM | Mô hình gợi ý (định dạng `.task` cho MediaPipe) | Tiếng Việt |
|---|---|---|
| 8 GB trở lên | Gemma 3n E4B (int4) | Tốt |
| 6 GB | Gemma 3n E2B (int4) | Khá tốt |
| 4 GB | Gemma 3 1B (int4) | Hiểu được câu đơn giản |

Tải từ Hugging Face, trang `litert-community` (cần đăng nhập và chấp nhận điều khoản Gemma). Prompt hiện dùng định dạng hội thoại của Gemma (`<start_of_turn>`); nếu đổi sang dòng mô hình khác như Qwen thì sửa `buildPrompt` cho khớp.

## Cách chạy

1. Cài Android Studio (bản mới), chọn **Open** thư mục này. Android Studio tự tải Gradle và SDK.
   Trước khi build: vào https://github.com/k2-fsa/sherpa-onnx/releases, tải file `sherpa-onnx-<phiên bản>.aar`,
   đổi tên thành `sherpa-onnx.aar` và đặt vào thư mục `app/libs/`. Thiếu file này app sẽ không build được.
2. Bật Developer options + USB debugging trên điện thoại, cắm cáp, bấm **Run**.
3. Chép mô hình vào máy:
   ```
   adb push gemma-3n-E4B-it-int4.task /sdcard/Android/data/vn.phamvu.trolyai/files/model.task
   ```
4. Mô hình nhận giọng chủ máy: cũng ở trang releases trên, mục **speaker-recongition-models**, tải
   `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` rồi:
   ```
   adb push 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx /sdcard/Android/data/vn.phamvu.trolyai/files/speaker.onnx
   ```
   Mở app, bấm **🎙️ Giọng của tôi**, xác nhận vân tay, đọc 5 câu mẫu.
5. Đọc biến động số dư: bấm **🏦 Đọc biến động số dư**, bật "Trợ lý AI" trong danh sách Truy cập thông báo.
6. Để nhận giọng nói offline: Cài đặt → Google → Giọng nói → Nhận dạng giọng nói ngoại tuyến → tải Tiếng Việt.
7. Để nhắc đúng phút trên Android 12+: Cài đặt → Ứng dụng → Trợ lý AI → cho phép **Báo thức và lời nhắc**.

Thử nói:
- "Nhớ giúp tôi là tôi dị ứng tôm"
- "Nhắc tôi uống thuốc lúc 9 giờ tối nay"
- "Đặt báo thức 6 giờ 30 sáng mai"
- "Tôi bị dị ứng gì nhỉ?"
- "Tiền nhà 5 triệu, đóng ngày 5 hằng tháng"
- "Tôi đóng tiền nhà rồi"
- "Tháng này tôi nộp tiết kiệm chưa?"

## Người bạn hiểu bạn hơn mỗi ngày

Mô hình AI là file cố định, không tự thay đổi. Trợ lý "hiểu bạn hơn" nhờ trí nhớ ngày càng đầy đủ:

- **Tính cách bạn bè:** lần đầu trợ lý hỏi tên bạn, cách xưng hô (mình - bạn, em - anh, tớ - cậu…) và tên bạn muốn đặt cho nó. Nó nói chuyện như bạn thân: hỏi han lại chuyện cũ, thật thà, không bịa.
- **Học ban đêm** (`learn/LearnWorker.kt`): mỗi ngày một lần, khi máy **đang sạc và rảnh**, trợ lý đọc lại các cuộc trò chuyện mới và tự ghi thêm điều đáng nhớ về bạn và thói quen của bạn. Không tốn pin ban ngày.
- **Hồ sơ về bạn:** mỗi 7 ngày, trợ lý tóm gọn mọi điều đã biết thành hồ sơ dưới 150 chữ, đi kèm mọi câu hỏi.
- **👍 / 👎:** mỗi câu trả lời có hai nút. Bấm 👎 rồi nói chỗ sai, trợ lý ghi thành **quy tắc** và luôn tuân theo từ đó.
- **Tìm trí nhớ:** so từ khoá không dấu ("tom" khớp "tôm"), ưu tiên điều mới, luôn kèm hồ sơ và quy tắc.

Thử: "Gọi tôi là Vũ, xưng em nhé, còn em tên là Mây", rồi bấm 👎 một câu và nói "lần sau trả lời ngắn thôi".

## Khoản chi định kỳ

- Lần đầu mở app, trợ lý hỏi bạn có những khoản cố định nào. Mỗi khoản lưu tên, ngày hạn, số tiền, từ khoá.
- 9 giờ sáng mỗi ngày app kiểm tra: khoản nào còn 2 ngày, đến hạn hoặc quá hạn mà chưa trả thì hiện thông báo có nút **Đã trả**.
- Đánh dấu đã trả bằng 3 cách: nói với trợ lý, bấm nút trên thông báo, hoặc tự nhận từ thông báo ngân hàng.
- Thông báo ngân hàng: app chỉ đọc thông báo đang hiện trên máy, **không đăng nhập ngân hàng**. Thông báo nào không phải giao dịch tiền ra thì bỏ qua ngay, không lưu. Giao dịch tiền ra được đoán theo từ khoá trong nội dung chuyển khoản (ví dụ "tien nha") hoặc số tiền lệch dưới 5%, rồi hỏi bạn "Có phải khoản Tiền nhà không?" với nút Đúng / Không phải.
- Khi bạn hỏi về tiền, trợ lý chỉ trả lời dựa trên sổ này, không đoán.

## Bảo mật

- **Khoá app:** mở app phải xác nhận vân tay, khuôn mặt hoặc mã PIN của máy. Rời app quá 1 phút thì khoá lại.
- **Lệnh nhạy cảm** (gọi điện, soạn tin, xoá trí nhớ) luôn hỏi xác nhận trước khi làm, kể cả khi app đang mở. Danh sách nằm ở biến `sensitive` trong `ActionExecutor.kt`.
- Máy chưa cài khoá màn hình thì app vẫn mở được nhưng mọi lệnh nhạy cảm bị từ chối.
- **Nhận giọng:** nếu đã đăng ký giọng, lệnh nhạy cảm sẽ nghe bạn nói một câu trong 3 giây. Giọng khớp (độ giống ≥ 0,6) thì làm luôn, không khớp thì hỏi vân tay. Đăng ký giọng luôn phải qua vân tay trước. Ngưỡng chỉnh ở `VoiceId.threshold`: thử với chính bạn và người nhà để chọn số phù hợp. Đây là lớp tiện lợi, không thay được vân tay, vì có thể bị lừa bằng ghi âm.
- **Mã hoá:** toàn bộ dữ liệu (trí nhớ, sổ chi, giao dịch, dấu vân giọng, lịch sử chat) nằm trong file SQLite mã hoá AES-256 bằng SQLCipher. Mật khẩu ngẫu nhiên, được khoá bởi một khoá nằm trong chip bảo mật của máy (Android Keystore), nên chép file sang máy khác cũng không đọc được.
- **Không sao lưu:** tắt sao lưu lên Google Drive và chép sang máy mới. Hệ quả: đổi máy hoặc xoá app là mất dữ liệu.
- **Miễn phí 100%:** mọi thư viện và mô hình đều miễn phí, mã nguồn mở; không máy chủ, không thuê bao.
- Thử: "Quên chuyện tôi dị ứng tôm đi".

## Hướng phát triển tiếp

- **Chế độ online tuỳ chọn**: thêm một `LlmEngine` gọi API mạnh hơn khi có mạng, rơi về mô hình offline khi mất mạng.
- **Thêm hành động**: lịch (CalendarContract), ghi chú, bật đèn pin, mở app theo tên.
- **Nhắc lặp lại** (hằng ngày, hằng tuần) và màn hình quản lý trí nhớ / nhắc nhở.
- **Xuất / nhập dữ liệu có mật khẩu** để chuyển sang máy mới mà không cần Google Drive.
- **Tìm trí nhớ theo ý nghĩa** bằng mô hình EmbeddingGemma (miễn phí, hiểu tiếng Việt), để "tôi ăn được gì?" tìm ra "dị ứng tôm".
- **Màn hình trí nhớ** để xem, sửa, xoá những gì trợ lý đã tự học.
- **Màn hình sổ chi** để xem, sửa, xoá các khoản và lịch sử đã trả.
- **Từ khoá đánh thức** ("Này trợ lý") bằng một mô hình nhỏ chạy nền.
