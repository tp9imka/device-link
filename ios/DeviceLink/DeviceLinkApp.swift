import SwiftUI
import UIKit
import UserNotifications

@main
struct DeviceLinkApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = LinkModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .onOpenURL { model.open($0.absoluteString) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { model.open(url.absoluteString) }
                }
                .onAppear { delegate.attach(model) }
        }
        .onChange(of: phase) { _, phase in
            switch phase {
            case .active: model.setActive(true)
            case .background: delegate.finishInBackground(model)
            default: break
            }
        }
    }
}

/// Push registration (optional APNs wake-ups) and a short background grace period for in-flight clips.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private weak var model: LinkModel?
    /// A token that arrived before the model was attached.
    private var token: Data?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // "Copy" on the push applies the waiting item without opening the app.
        let copy = UNNotificationAction(identifier: "COPY", title: "Copy", options: [])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: "DEVICELINK_ITEM", actions: [copy], intentIdentifiers: [], options: []),
        ])
        // Getting the APNs token shows no prompt; the permission prompt waits for the first link.
        application.registerForRemoteNotifications()
        return true
    }

    @MainActor func attach(_ model: LinkModel) {
        self.model = model
        if let token { model.registerPush(token: token) }
        model.onLinkConfirmed = { [weak model] in
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in
                Task { @MainActor in model?.asksForNotifications = false }
            }
        }
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let undecided = settings.authorizationStatus == .notDetermined
            Task { @MainActor in model.asksForNotifications = undecided }
        }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in
            token = deviceToken
            model?.registerPush(token: deviceToken)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {}

    /// The relay's push says "an item is waiting"; tapping it opens the app, which fetches and copies it.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [] // In the foreground the receiver loop already picked it up.
    }

    /// Keeps receiving for ~25 s after leaving the app so a clip copied right then still lands.
    /// Notification tap opens the app (the receive loop copies); the Copy action fetches and copies in the background.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.actionIdentifier == "COPY" else { return }
        // drain, not receiveOnce until 0: a receipt or duplicate served first would stop before the clip.
        // Failures leave the item waiting; opening the app fetches it.
        guard let client = try? await LinkEnvironment.client() else { return }
        _ = try? await drainWaiting(client)
    }

    @MainActor func finishInBackground(_ model: LinkModel) {
        var task: UIBackgroundTaskIdentifier = .invalid
        func end() {
            if UIApplication.shared.applicationState != .active { model.setActive(false) }
            if task != .invalid { UIApplication.shared.endBackgroundTask(task); task = .invalid }
        }
        task = UIApplication.shared.beginBackgroundTask(withName: "DeviceLink receive") { Task { @MainActor in end() } }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 25_000_000_000)
            end()
        }
    }
}
