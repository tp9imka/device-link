import DeviceLinkKit
import UserNotifications

/// Turns the relay's content-free push ("New item from a linked device") into a real preview:
/// fetches the sealed item by ID, decrypts it on the device and shows "From Pixel: …" with a
/// Copy action. Nothing is acknowledged here; the app (or the Copy action) applies the item.
final class NotificationService: UNNotificationServiceExtension {
    private var handler: ((UNNotificationContent) -> Void)?
    private var content: UNMutableNotificationContent?

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        handler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent) ?? UNMutableNotificationContent()
        self.content = content
        content.categoryIdentifier = "DEVICELINK_ITEM"
        guard let messageId = request.content.userInfo["m"] as? String else { return contentHandler(content) }
        Task { @MainActor in
            if let client = try? LinkEnvironment.makeClient(), let item = try? await client.preview(messageId: messageId) {
                content.title = "From \(item.peer.name)"
                switch item.kind {
                case .text: content.body = item.sensitive ? "Sensitive content · tap Copy" : String((item.text ?? "").prefix(180))
                case .image: content.body = "Image · tap Copy"
                default: content.body = "\(item.fileName ?? "File") · open to share"
                }
            }
            contentHandler(content)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        if let handler, let content { handler(content) }
    }
}
