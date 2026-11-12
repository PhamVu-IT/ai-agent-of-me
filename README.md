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
| Hỗ trợ phỏng vấn | `interview/` | Chế độ ẩn: nghe tiếng Anh, gợi ý trả lời trong ô nổi nhỏ |
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

## Khoản chi định kỳ

- Lần đầu mở app, trợ lý hỏi bạn có những khoản cố định nào. Mỗi khoản lưu tên, ngày hạn, số tiền, từ khoá.
- 9 giờ sáng mỗi ngày app kiểm tra: khoản nào còn 2 ngày, đến hạn hoặc quá hạn mà chưa trả thì hiện thông báo có nút **Đã trả**.
- Đánh dấu đã trả bằng 3 cách: nói với trợ lý, bấm nút trên thông báo, hoặc tự nhận từ thông báo ngân hàng.
- Thông báo ngân hàng: app chỉ đọc thông báo đang hiện trên máy, **không đăng nhập ngân hàng**. Thông báo nào không phải giao dịch tiền ra thì bỏ qua ngay, không lưu. Giao dịch tiền ra được đoán theo từ khoá trong nội dung chuyển khoản (ví dụ "tien nha") hoặc số tiền lệch dưới 5%, rồi hỏi bạn "Có phải khoản Tiền nhà không?" với nút Đúng / Không phải.
- Khi bạn hỏi về tiền, trợ lý chỉ trả lời dựa trên sổ này, không đoán.

## Chế độ ẩn hỗ trợ phỏng vấn tiếng Anh

Bấm **🎧 Phỏng vấn** trên màn hình chat.

```
 Micro (nghe liên tục, offline)
   │  sherpa-onnx streaming zipformer (tiếng Anh)
   ▼
 Hết câu (im lặng 1 giây) ── giọng chủ máy? ── có → bỏ qua (bạn đang trả lời)
   │ không
   ▼
 Gộp các đoạn của cùng một câu hỏi (đợi 1,2 giây)
   │  InterviewCoach: câu hỏi + hồ sơ của bạn + vài lượt trước → mô hình AI trên máy
   ▼
 Ô nổi nhỏ màu tối:  VI: <câu hỏi dịch sang tiếng Việt>
                     - 3 ý chính bằng tiếng Anh đơn giản
                     Say: <câu mở đầu để nói ngay>
```

Cài đặt một lần:
1. Tải mô hình nghe tiếng Anh: trang releases của k2-fsa/sherpa-onnx, mục **asr-models**, file
   `sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2` (miễn phí). Giải nén rồi:
   ```
   adb push sherpa-onnx-streaming-zipformer-en-2023-06-26 /sdcard/Android/data/vn.phamvu.trolyai/files/asr-en
   ```
   Không cần đổi tên file; app tự chọn bản `int8` nếu có.
2. Trong màn hình Phỏng vấn: điền **Hồ sơ của bạn** (vị trí ứng tuyển, kinh nghiệm, dự án, điểm mạnh). Gợi ý chỉ dựa trên hồ sơ này, mô hình được dặn không bịa kinh nghiệm.
3. Bấm **Cấp quyền** để cho phép hiện trên ứng dụng khác, rồi **Kiểm tra lại**.
4. Nên đăng ký **🎙️ Giọng của tôi** để máy không coi câu bạn nói là câu hỏi.

Dùng:
- **Bật chế độ ẩn**, rồi chuyển sang app khác hoặc tắt màn hình chat. Ô gợi ý rộng 260dp, chữ nhỏ, nền tối trong suốt nhẹ.
- Chạm ô: thu thành một chấm nhỏ; chấm chuyển **xanh** khi có gợi ý mới. Kéo để đặt ở góc kín đáo. Giữ 1 giây để tắt.
- Thông báo bắt buộc của Android khi dùng micro ở nền được đặt ở mức thấp nhất (không kêu, không rung), có nút **Dừng**.
- Phần **Luyện tập** cho gõ câu hỏi để thử gợi ý mà không cần micro.

Giới hạn cần biết:
- Micro chỉ nghe âm thanh trong phòng. Hợp nhất: phỏng vấn trên máy tính bằng loa ngoài (hoặc gặp trực tiếp), điện thoại đặt gần. Nếu dùng tai nghe trên máy tính thì điện thoại không nghe được người hỏi.
- Khi chính điện thoại đang có cuộc gọi (Zoom, Meet, Teams, gọi thường), Android dành micro cho cuộc gọi nên app không nghe được.
- Gemma 3n E4B trên S23 Ultra mất khoảng vài giây cho mỗi gợi ý; câu trả lời được giữ rất ngắn để nhanh.
- Mô hình và nhận giọng đều có thể nghe nhầm; dòng chữ xám phía trên cho biết máy đã nghe được gì.
- Nhiều công ty không cho dùng công cụ trợ giúp trong lúc phỏng vấn; hãy xem quy định của họ.

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

- **Tìm trí nhớ theo ý nghĩa**: thay tìm từ khoá bằng embedding (MediaPipe Text Embedder) để "tôi ăn gì được" vẫn tìm ra "dị ứng tôm".
- **Chế độ online tuỳ chọn**: thêm một `LlmEngine` gọi API mạnh hơn khi có mạng, rơi về mô hình offline khi mất mạng.
- **Thêm hành động**: lịch (CalendarContract), ghi chú, bật đèn pin, mở app theo tên.
- **Nhắc lặp lại** (hằng ngày, hằng tuần) và màn hình quản lý trí nhớ / nhắc nhở.
- **Xuất / nhập dữ liệu có mật khẩu** để chuyển sang máy mới mà không cần Google Drive.
- **Màn hình sổ chi** để xem, sửa, xoá các khoản và lịch sử đã trả.
- **Từ khoá đánh thức** ("Này trợ lý") bằng một mô hình nhỏ chạy nền.
