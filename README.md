# Camera Giám Sát Gia Đình

Ứng dụng biến 1 điện thoại cũ thành camera giám sát, xem trực tiếp từ điện thoại
khác. Video được ghi lại theo từng đoạn 15 phút và lưu **vĩnh viễn ngay trên
chính máy xem** — không dùng Google Drive hay bất kỳ server lưu trữ nào ở giữa.

## Kiến trúc

- **Máy Camera (điện thoại đặt cố định):** `CameraActivity` → `CameraStreamService`
  — tự chọn ống kính sau có góc nhìn rộng nhất, quay 1280x720@20fps, phục vụ
  tối đa 4 máy xem cùng lúc qua WebRTC.
- **Máy Xem:** `ViewerActivity` → `ViewerRecordingService` (chạy nền, không phụ
  thuộc màn hình có mở hay không)
  - Xem trực tiếp nhiều camera cùng lúc (danh sách hoặc lưới 2x2).
  - Ghi hình tối đa vài camera song song, cắt đoạn 15 phút (`SegmentedRecorder`),
    căn đúng mốc giờ tường (:00/:15/:30/:45) để ghép hàng đúng giữa các camera.
  - `storage/LocalVideoStore.kt`: lưu video vĩnh viễn trong thư mục riêng của
    app trên máy xem, có thể đặt số ngày tự xoá video cũ để đỡ đầy máy.
  - `VideoGalleryActivity` + `MultiViewPlayerActivity`: xem lại video đã lưu,
    phát thẳng từ file cục bộ (không cần tải/stream qua mạng).
- **Signaling (ghép nối 2 máy):** Firebase Realtime Database — không cần tự
  dựng server riêng.
- **Truyền video trực tiếp:** WebRTC.

## Bước 1 — Tạo dự án Firebase

1. Tạo project trên [Firebase Console](https://console.firebase.google.com),
   thêm app Android với package name khớp `applicationId` trong
   `app/build.gradle.kts`.
2. Tải file `google-services.json` từ Firebase Console, đặt vào `app/`
   (file này đã có trong `.gitignore`, sẽ không bị commit lên repo public).
3. Firebase Console → **Realtime Database** → **Create database** → chọn
   **test mode** để bắt đầu (siết lại rule khi dùng thật, xem gợi ý bên dưới).

### Gợi ý Realtime Database Rules (siết bảo mật cơ bản)

```json
{
  "rules": {
    "rooms": {
      "$roomCode": {
        ".read": true,
        ".write": true,
        ".validate": "newData.hasChildren(['status'])"
      }
    }
  }
}
```

Đây là mức tối thiểu để chạy demo. Khi triển khai thật, nên thêm Firebase
Authentication (ẩn danh) và giới hạn quyền ghi theo UID để tránh người lạ
ghi đè phòng của người khác.

## Bước 2 — Build APK bằng GitHub Actions (không cần máy tính cài Android Studio)

1. Vào repo → **Settings → Secrets and variables → Actions → New repository
   secret**, đặt tên `GOOGLE_SERVICES_JSON_BASE64`, dán vào giá trị là chuỗi
   base64 của file `google-services.json` bạn tải ở Bước 1:
   ```
   base64 -w0 google-services.json
   ```
   Dán kết quả vào ô Secret — **không dán vào bất kỳ file nào được commit lên
   repo**, kể cả README này.
2. Vào tab **Actions**, chạy workflow **Build Debug APK** (hoặc chỉ cần push
   lên nhánh `main`).
3. Sau khi build xong, mở run vừa chạy → mục **Artifacts** → tải APK debug về,
   cài vào 2 máy.

## Bước 3 — Cài đặt trên 2 máy

**Máy Camera (điện thoại đặt cố định, ví dụ điện thoại cũ):**
1. Mở app → chọn vai trò "Máy Camera".
2. Đồng ý, cấp quyền Camera khi được hỏi.
3. Mã ghép nối 6 số hiện ra — mã này **cố định vĩnh viễn** cho máy này, đọc
   cho người dùng máy xem.
4. Vào **Cài đặt → Pin → Không tối ưu hoá pin** cho app này, để camera không
   bị hệ thống tắt ngầm khi màn hình tắt lâu.

**Máy Xem:**
1. Mở app → chọn vai trò "Máy Xem".
2. Bấm thêm camera → nhập mã 6 số của Máy Camera.
3. Bật ghi hình cho camera muốn lưu lại (tối đa vài camera cùng lúc).
4. Vào **Cài đặt → Pin → Không tối ưu hoá pin** cho app này, để việc ghi hình
   nền không bị hệ thống dừng.
5. Video đã ghi xem lại trong "Xem video đã lưu" — có thể chỉnh số ngày tự
   xoá video cũ trong mục cài đặt cạnh đó.

## Nguyên tắc thiết kế cần giữ nguyên khi chỉnh sửa

- Không dùng Google Drive hay bất kỳ dịch vụ lưu trữ đám mây nào — video chỉ
  lưu trên chính máy xem.
- Không dựng server proxy nào để phát lại video — luôn phát thẳng từ file cục bộ.
- Mỗi đoạn ghi hình dài đúng 15 phút, căn theo mốc giờ tường (:00/:15/:30/:45)
  để các camera khác nhau ghép đúng hàng khi xem lại.
- Notification "Đang ghi hình / đang xem" luôn hiển thị trong lúc service nền
  đang chạy, không được ẩn.
