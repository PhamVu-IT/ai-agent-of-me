# Trợ lý AI cá nhân offline cho Android

Ứng dụng trò chuyện bằng tiếng Việt (gõ hoặc nói), hiểu yêu cầu và thực hiện, ghi nhớ thông tin về bạn, và nhắc nhở đúng giờ. Mọi thứ chạy trên điện thoại, không cần mạng.

## Kiến trúc

```
 Bạn (gõ / nói)
     │  sherpa-onnx zipformer-vi (offline) → dự phòng SpeechRecognizer của máy
     ▼
 ChatViewModel ──► Assistant ("bộ não")
                     │ 1. lấy trí nhớ liên quan + lịch sử chat từ Room
                     │ 2. ghép prompt, gọi mô hình AI trên máy (MediaPipe hoặc LiteRT-LM)
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

Ngoài file `.task`, app còn chạy được file **`.litertlm`** bằng thư viện LiteRT-LM, ví dụ
[Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (khoảng 2,6 GB, trên GPU máy đời mới
chữ đầu tiên ra sau chưa tới 1 giây). App tự chọn thư viện theo đuôi file: `.task` chạy bằng MediaPipe, `.litertlm`
chạy bằng LiteRT-LM; cả hai đều thử GPU trước, lỗi thì tự chuyển sang CPU. Mỗi lúc chỉ giữ một mô hình: chọn file mới
thì file cũ bị thay. Dòng ⏱ dưới câu trả lời cho biết đang chạy bằng thư viện nào, trên GPU hay CPU.

Tải từ Hugging Face, trang `litert-community` (cần đăng nhập và chấp nhận điều khoản Gemma). Prompt hiện dùng định dạng hội thoại của Gemma (`<start_of_turn>`); nếu đổi sang dòng mô hình khác như Qwen thì sửa `buildPrompt` cho khớp.

## Cách chạy

1. Cài Android Studio (bản mới), chọn **Open** thư mục này. Android Studio tự tải Gradle và SDK.
   Trước khi build: vào https://github.com/k2-fsa/sherpa-onnx/releases, tải file `sherpa-onnx-<phiên bản>.aar`,
   đổi tên thành `sherpa-onnx.aar` và đặt vào thư mục `app/libs/`. Thiếu file này app sẽ không build được.
   Chọn bản Android thường (`sherpa-onnx-1.x.y.aar`), không phải file `.jar` hay bản có chữ `rknn`/`qnn`. Chỉ để
   một file `.aar` trong `app/libs/`. Khi build, Gradle tự kiểm tra file này có thư viện `libsherpa-onnx-jni.so`
   cho điện thoại 64-bit hay không và báo lỗi ngay nếu sai. Sau khi thay file aar, bấm **Build > Clean Project**
   rồi mới **Run**, nếu không app có thể vẫn báo "library libsherpa-onnx-jni.so not found" lúc lưu giọng.
2. Bật Developer options + USB debugging trên điện thoại, cắm cáp, bấm **Run**.
3. Đưa mô hình vào app, chọn một trong hai cách:
   - **Chọn ngay trong app (dễ nhất):** tải file `.task` hoặc `.litertlm` về điện thoại (thường nằm trong thư mục Download),
     mở app, bấm **Chọn file mô hình** trên thẻ "Chưa có mô hình AI" (hoặc chip **🧠 Mô hình** để đổi
     mô hình khác) rồi chọn file. App chép file vào bộ nhớ riêng, hiện phần trăm tiến độ (file vài GB mất
     vài phút, cứ để app mở), sau đó tự nạp mô hình. Chép xong có thể xoá bản trong Download cho đỡ tốn chỗ.
     Cần trống khoảng bằng dung lượng file cộng thêm 200 MB.
   - **Dùng cáp và adb:**
     ```
     adb push gemma-3n-E4B-it-int4.task /sdcard/Android/data/vn.phamvu.trolyai/files/model.task
     # hoặc mô hình LiteRT-LM:
     adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/vn.phamvu.trolyai/files/model.litertlm
     ```
   Nhận file `.task` (hoặc `.bin` kiểu cũ) cho MediaPipe và file `.litertlm` cho LiteRT-LM. Nếu dùng adb, chỉ để
   một trong hai file `model.task` / `model.litertlm`; nếu có cả hai, app dùng file chép vào sau cùng.
4. Mô hình nhận giọng chủ máy: cũng ở trang releases trên, mục **speaker-recongition-models**, tải
   `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` về điện thoại. Mở app, bấm chip **🧠 Mô hình**,
   mục "Nhận giọng chủ máy" bấm **Chọn file .onnx** rồi chọn file vừa tải (tên gì cũng được, app tự lưu thành
   `speaker.onnx`). Nút này cũng hiện ngay trong **🎙️ Giọng của tôi** nếu chưa có mô hình.
   Sau đó bấm **🎙️ Giọng của tôi**, xác nhận vân tay, đọc 5 câu mẫu.
   Vẫn dùng adb được nếu muốn:
   ```
   adb push 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx /sdcard/Android/data/vn.phamvu.trolyai/files/speaker.onnx
   ```

   > Vì sao phải chọn trong app: từ Android 11, trình quản lý file (kể cả My Files của Samsung) không còn
   > chép được vào thư mục `Android/data`. Chip **🧠 Mô hình** liệt kê các mô hình, có ✅ / ❌ cho biết cái nào đã sẵn sàng.
5. Đọc biến động số dư: bấm **🏦 Đọc biến động số dư**, bật "Trợ lý AI" trong danh sách Truy cập thông báo.
6. Để nói tiếng Việt khi không có mạng (không cần app Google): tải
   `sherpa-onnx-zipformer-vi-int8-2025-04-20.tar.bz2` ở [releases của k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)
   (bản nhỏ hơn: `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2`), rồi vào **🧠 Mô hình → Nghe tiếng Việt offline**
   bấm **Chọn file** và chọn thẳng file đó, không cần giải nén. Nút 🎤 sẽ nghe ngay trong app, nói xong ngừng khoảng
   1 giây là app tự nhận dạng.
   Chưa có mô hình này thì app dùng bộ nhận giọng của máy (cần app Google hoặc dịch vụ giọng nói của Samsung,
   và thường cần mạng). App không còn mở hộp thoại "Voice search" của Google, nên không còn lỗi
   "Voice search Google not available" khi mở app.
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

## Gọi trợ lý nhanh

Mỗi lần mở, sau khi mở khoá, trợ lý **tự nghe ngay**, không cần bấm mic. Các cách mở nhanh:

- **Nút nguồn 2 lần** (Samsung): Cài đặt → Tính năng nâng cao → Phím cạnh → Nhấn hai lần → Mở ứng dụng → Trợ lý AI.
- **Thanh kéo xuống:** kéo thanh thông báo, bấm ✏️ (sửa nút), kéo nút **Trợ lý AI** vào.
- **Widget:** giữ lâu màn hình chính → Tiện ích → Trợ lý AI → **Nói với trợ lý**.
- **Trợ lý mặc định:** Cài đặt → Ứng dụng → Chọn ứng dụng mặc định → Ứng dụng trợ lý kỹ thuật số → Trợ lý AI. Sau đó vuốt chéo từ góc dưới màn hình (hoặc giữ nút Home nếu dùng thanh 3 nút) là gọi được.

## Khoản chi định kỳ

- Lần đầu mở app, trợ lý hỏi bạn có những khoản cố định nào. Mỗi khoản lưu tên, ngày hạn, số tiền, từ khoá.
- 9 giờ sáng mỗi ngày app kiểm tra: khoản nào còn 2 ngày, đến hạn hoặc quá hạn mà chưa trả thì hiện thông báo có nút **Đã trả**.
- Đánh dấu đã trả bằng 3 cách: nói với trợ lý, bấm nút trên thông báo, hoặc tự nhận từ thông báo ngân hàng.
- Thông báo ngân hàng: app chỉ đọc thông báo đang hiện trên máy, **không đăng nhập ngân hàng**. Thông báo nào không phải giao dịch tiền ra thì bỏ qua ngay, không lưu. Giao dịch tiền ra được đoán theo từ khoá trong nội dung chuyển khoản (ví dụ "tien nha") hoặc số tiền lệch dưới 5%, rồi hỏi bạn "Có phải khoản Tiền nhà không?" với nút Đúng / Không phải.
- Khi bạn hỏi về tiền, trợ lý chỉ trả lời dựa trên sổ này, không đoán.

## Tự nhắc ngày giỗ, sinh nhật, kỷ niệm

Trợ lý nhớ các ngày lặp lại hằng năm và **tự nhắc, không cần bạn hỏi lại**:

- **Ghi lại khi bạn kể**: nói hay gõ "giỗ ông nội mùng 10 tháng 3 âm", "sinh nhật vợ 20/10", "kỷ niệm ngày cưới 15/8/2015",
  "bố tôi mất ngày 3/5 âm"… là trợ lý lưu ngay vào sổ 📅 Ngày quan trọng (tìm bằng luật trên máy, không cần mô hình AI;
  AI cũng có hành động `add_event` cho câu nói khó hơn). Giỗ mặc định tính theo âm lịch.
- **Tìm lại lời kể cũ**: lần đầu mở bản mới, trợ lý đọc lại trí nhớ và tin nhắn cũ để tìm các ngày bạn đã kể từ trước.
- **Âm lịch offline**: đổi âm ⇄ dương theo thuật toán thiên văn của Hồ Ngọc Đức (giờ Hà Nội), có tháng nhuận.
  Tháng thiếu không có ngày 30 thì giỗ tính vào ngày 29.
- **Bản tin buổi sáng** (mặc định 7 giờ, đổi trong màn 📅): một thông báo gồm ngày âm hôm nay, mùng 1 / rằm,
  giỗ / sinh nhật / kỷ niệm sắp tới, khoản chi sắp đến hạn và lời nhắc trong ngày. Ngày quan trọng được nhắc
  trước 7 ngày (giỗ) hoặc 3 ngày (loại khác), trước 3 ngày, hôm trước và đúng ngày. Không có gì thì không làm phiền.
  Máy tắt lúc 7 giờ thì bật lên trước trưa vẫn nhận bù; khởi động lại máy hay cập nhật app không làm mất lịch.
- **Mở chat là nhắc**: trong khoảng cần nhắc, mở app là trợ lý tự nói trước (mỗi ngày một lần).
- **Màn 📅 Ngày quan trọng** (menu ⋮): xem theo ngày gần nhất, thêm, sửa, xoá. Ngày đã xoá thì trợ lý không tự thêm lại.
- Hỏi "sắp tới có giỗ ai không", "hôm nay âm lịch ngày mấy" là trả lời ngay từ dữ liệu trên máy.

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
   `sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2` (miễn phí) về điện thoại. Trong màn hình Phỏng vấn
   (hoặc chip **🧠 Mô hình**), mục "Nghe tiếng Anh", chọn một trong ba cách:
   - **Chọn file** rồi chọn thẳng file `.tar.bz2` vừa tải, không cần giải nén. App tự giải nén và chỉ giữ
     những file cần (mất một hai phút, để app mở).
   - **Chọn file** rồi giữ ngón tay để chọn nhiều file đã giải nén: `tokens.txt` và các file encoder / decoder / joiner `.onnx`.
   - **Chọn thư mục** đã giải nén. Android không cho chọn thẳng thư mục Download gốc, nên hãy để mô hình trong
     một thư mục con (ví dụ `Download/asr-en`).

   Không cần đổi tên file; nếu có cả bản thường và bản `int8`, app chỉ chép bản `int8` (nhẹ, nhanh hơn).
   Chọn thiếu file thì app báo còn thiếu phần nào để chọn thêm. Vẫn dùng adb được:
   ```
   adb push sherpa-onnx-streaming-zipformer-en-2023-06-26 /sdcard/Android/data/vn.phamvu.trolyai/files/asr-en
   ```
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

## Giao diện chat và tốc độ (học từ Google AI Edge Gallery)

Màn hình chat làm theo kiểu [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery):

- Bong bóng tin nhắn có "đuôi", tên và hình tròn của trợ lý trên câu trả lời, màu theo hình nền máy (Android 12+), tự đổi sáng / tối.
- Ba chấm "đang gõ…" trong lúc chờ chữ đầu tiên, rồi câu trả lời hiện dần từng chữ, màn hình tự bám theo dòng cuối.
- Nút gửi thành nút dừng ■ khi trợ lý đang viết; dừng thì giữ phần đã viết.
- Dưới mỗi câu trả lời: 👍 👎, 📋 chép, 🔊 đọc lại. Giữ lâu bong bóng để chép.
- Nút 📊 trên cùng mở màn Thống kê riêng (không chen vào màn chat): tốc độ token/giây, thời gian tới chữ đầu, số token,
  bộ nhớ ngữ cảnh đã dùng, RAM, thời gian nạp mô hình, trung bình và danh sách các câu gần đây.
- Các nút Thống kê, Mô hình, Giọng của tôi, Đọc biến động số dư, Phỏng vấn nằm trong menu ⋮ trên cùng.

Vì sao nhanh hơn: với mô hình `.litertlm`, app giữ một cuộc trò chuyện LiteRT-LM sống qua các lượt như Gallery.
Mô hình đã đọc phần vai trò, quy tắc, hồ sơ một lần, mỗi lượt sau chỉ đọc lời nhắn mới, nên chữ đầu tiên ra sớm hơn
nhiều. Cuộc trò chuyện tự làm mới (kèm 6 câu gần nhất) khi quy tắc / hồ sơ / tên thay đổi, khi sắp đầy 4096 token,
hoặc sau khi bấm dừng. Mô hình `.task` (MediaPipe) vẫn chạy như cũ.

**Trả lời bằng chữ bình thường như Gallery.** Trợ lý không còn trả lời dạng JSON. Khi cần làm việc gì (nhắc nhở, ghi nhớ,
gọi điện…) mô hình viết thêm một dòng cuối `[[LỆNH]] {...}`; app tự cắt dòng đó đi, không hiện ra, rồi thực hiện lệnh.
Mô hình nào vẫn quen trả JSON kiểu cũ thì app vẫn hiểu.

**Gửi ảnh và ghi âm cho AI** (nút ＋ cạnh ô nhập, như Gallery):
- 📷 Chụp ảnh hoặc 🖼️ Chọn ảnh (tối đa 5 ảnh), hỏi về ảnh: "đây là món gì", "đọc giúp hoá đơn này"…
- 🎙️ Ghi âm tối đa 30 giây để AI nghe (khác nút 🎤 là nói để gõ chữ). Bấm ▶ để nghe lại trước khi gửi.
- Cần mô hình `.litertlm` có phần xem ảnh và nghe, ví dụ Gemma 3n (`gemma-3n-E2B-it-int4.litertlm`) hoặc Gemma 4.
  App tự thử nạp phần ảnh / âm thanh; mô hình chỉ có chữ thì vẫn chạy bình thường và báo không xem ảnh được.
  Mô hình `.task` (MediaPipe) chưa hỗ trợ.
- Ảnh và đoạn ghi âm chỉ nằm trong RAM khi app đang mở, không lưu ra bộ nhớ máy; lịch sử chat chỉ ghi dấu 📷 / 🎙️.

File `ui/MessageBubbleShape.kt` lấy từ Gallery (Apache License 2.0, giữ nguyên ghi chú bản quyền của Google).

## Agent Skills và MCP (như Google AI Edge Gallery)

Menu ⋮ → **🧩 Skill & MCP**. Trợ lý thấy tên và mô tả của các skill / công cụ đang bật; khi cần thì tự gọi,
app chạy rồi đưa kết quả lại để trợ lý trả lời tiếp (tối đa 4 bước mỗi câu).

- **Skill có sẵn** (lấy từ Gallery, Apache License 2.0, trong `app/src/main/assets/skills/`): tra Wikipedia
  (`query-wikipedia`), bản đồ (`interactive-map`, hiện ngay trong chat), tạo mã QR (`qr-code`, hiện ảnh), gửi email
  (`send-email`, mở app email để bạn tự bấm gửi).
- **Thêm skill**: từ đường link tới thư mục skill (có `SKILL.md`), hoặc chọn thư mục skill trong máy. Định dạng giống hệt
  Gallery: `SKILL.md` (phần đầu `name`, `description`) và tuỳ chọn `scripts/index.html` có hàm
  `window.ai_edge_gallery_get_result(data, secret)`. Skill cần khoá bí mật (`require-secret: true`) thì nhập khoá ở màn này.
- **Máy chủ MCP**: thêm link Streamable HTTP (thường kết thúc bằng `/mcp`), kèm một header xác thực nếu cần
  (ví dụ `X-Goog-Api-Key`). App tự lấy danh sách công cụ; bật / tắt từng công cụ. Mô hình nhỏ chỉ có 4096 token,
  nên chỉ bật những công cụ bạn cần.
- App có quyền Internet nhưng chỉ dùng khi một skill hay máy chủ MCP cần; trò chuyện thường vẫn chạy offline.

## ⚙️ Cài đặt mô hình và giọng đọc

Mở ⋮ › ⚙️ Cài đặt (giống phần "Configs" của Google AI Edge Gallery):

- **Chạy trên**: Tự chọn (thử GPU rồi CPU), GPU, hoặc CPU. Đổi mục này thì mô hình được nạp lại ở câu hỏi sau.
- **Temperature, Top-K, Top-P**: chỉnh độ "sáng tạo" của câu trả lời. Mặc định giống Gallery (1,0 / 64 / 0,95).
- **Chế độ suy nghĩ**: mô hình (như Gemma 4) nghĩ trước rồi mới trả lời. Phần suy nghĩ hiện trong khung 💭 trên câu trả lời, bấm để mở xem.
- **Giọng đọc**: bật / tắt tự đọc câu trả lời, tốc độ, cao độ, chọn giọng tiếng Việt có trong máy, nút nghe thử.

Mô hình `.task` (MediaPipe) chưa dùng được temperature / top-k / top-p và chế độ suy nghĩ; cần mô hình `.litertlm`.

## 🎧 Chép lời ghi âm (như Audio Scribe của Gallery)

Mở ⋮ › 🎧 Chép lời ghi âm, chọn file (mp3, m4a, ogg, wav… cả phần tiếng của video) hoặc thu âm tới 30 phút, rồi chọn:

- **Chép lời** hoặc **Dịch** sang Việt / Anh / Trung / Nhật / Hàn.
- **AI nghe**: mô hình Gemma 3n / Gemma 4 (.litertlm) nghe trực tiếp, hiểu nhiều thứ tiếng.
- **Nhận dạng tiếng Việt**: bộ nhận dạng offline (cài ở 🧠 Mô hình), nhanh hơn, chỉ nghe tiếng Việt; muốn dịch thì AI dịch phần chữ.

App cắt bản ghi thành từng đoạn khoảng 25 giây (cắt ở chỗ im nhất để khỏi đứt chữ), bản chép hiện dần kèm mốc thời gian, có nút chép và chia sẻ. Mọi thứ chạy trong máy.

## 📥 Tải mô hình trong app và 📏 đo tốc độ

Vào ⋮ › 🧠 Mô hình › **📥 Tải mô hình AI và đo tốc độ**:

- Danh sách mô hình lấy từ Google AI Edge Gallery: Gemma 4 E2B / E4B, Gemma 3n E2B / E4B, Gemma 3 1B, Qwen 2.5 1.5B và DeepSeek R1 1.5B. Mỗi mô hình ghi rõ dung lượng, RAM cần có, và có hiểu ảnh, âm thanh hay có chế độ suy nghĩ không. Mô hình nặng hơn RAM của máy thì có cảnh báo.
- Bấm **Tải về** là app tải thẳng từ Hugging Face, đúng phiên bản Gallery đã thử. Việc tải chạy ở nền và có thông báo tiến độ. Mất mạng thì app tải tiếp từ chỗ đang dở. Tải xong, app tự thay mô hình đang dùng.
- Mô hình đánh dấu 🔑 cần một bước chuẩn bị:
  1. Đăng nhập huggingface.co và bấm đồng ý điều khoản ở trang của mô hình.
  2. Tạo mã truy cập (Access Token) loại Read.
  3. Dán mã vào app. Mã chỉ lưu trong máy.
- **📏 Đo tốc độ**: mô hình đang dùng tóm tắt một đoạn văn cố định 3 lần. Kết quả cho biết tốc độ viết (token/giây), thời gian tới chữ đầu tiên, tốc độ đọc đề và thời gian nạp. Đổi GPU / CPU ở ⚙️ Cài đặt rồi đo lại để so sánh.

## 📱 Điều khiển điện thoại bằng lời (như Mobile Actions của Gallery)

Nói hoặc gõ vào chat, ví dụ:

- "Bật đèn pin" hoặc "tắt đèn pin"
- "Mở Wi‑Fi", "mở Bluetooth", "mở cài đặt vị trí"
- "Mở Zalo", "mở máy tính"
- "Hẹn giờ 5 phút", "tăng âm lượng", "tắt tiếng", "mở camera"
- "Thêm lịch họp 9 giờ sáng mai", "lưu số 0912… tên anh Nam", "chỉ đường tới Hồ Gươm"

Câu ngắn hay gặp được làm ngay mà không cần chờ AI. Cách này chạy được cả khi chưa có mô hình. Câu dài hơn thì AI hiểu ý rồi gọi cùng các lệnh đó.

Android không cho app tự bật Wi‑Fi hay chỉnh độ sáng. Với những việc này, app mở đúng bảng cài đặt để bạn bấm một lần. Lịch và danh bạ được mở sẵn thông tin để bạn xem lại rồi tự bấm Lưu.

## 🖼️ Tìm ảnh bằng chữ, offline

Mở ⋮ › 🖼️ Tìm ảnh, cho phép đọc ảnh rồi bấm **Bắt đầu đọc ảnh**. AI (Gemma 3n / Gemma 4 .litertlm) xem từng ảnh một lần, mới nhất trước, và viết mô tả ngắn kèm từ khoá tiếng Việt và tiếng Anh. Mỗi ảnh mất vài giây. Bạn có thể dừng bất cứ lúc nào, lần sau app đọc tiếp.

Đọc xong thì tìm bằng lời, ví dụ "con mèo", "sinh nhật", "bãi biển năm 2023", "hoá đơn tiền điện tháng 5". Gõ không dấu cũng tìm được. Bấm vào ảnh để mở, bấm ⓘ để xem mô tả AI đã viết.

Bật **Tự đọc ảnh mới khi đang sạc** thì mỗi ngày, lúc máy đang sạc và bạn không dùng, app đọc thêm tối đa 200 ảnh. Mô tả chỉ lưu trong bộ nhớ riêng của app. Ảnh không gửi đi đâu.

## 📸 Học ngoại ngữ qua camera

Mở ⋮ › 📸 Học ngoại ngữ, chọn ngôn ngữ (Anh, Trung, Nhật, Hàn, Pháp) rồi chụp hay chọn ảnh đồ vật quanh bạn. AI xem ảnh và dạy tối đa 5 thứ nổi bật nhất trong đó. Mỗi thứ có:

- từ bằng ngôn ngữ đang học
- phiên âm (IPA, pinyin, romaji…)
- nghĩa tiếng Việt
- một câu ví dụ kèm lời dịch

Bấm 🔊 để nghe đọc bằng giọng của ngôn ngữ đó. Bấm ☆ để lưu vào 📒 Sổ từ, rồi dùng 🃏 Ôn từ: app hiện nghĩa tiếng Việt, bạn gõ lại từ. Cần mô hình xem được ảnh (Gemma 3n / Gemma 4 .litertlm). Muốn nghe đọc thì máy cần có giọng đọc của ngôn ngữ đó.

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
