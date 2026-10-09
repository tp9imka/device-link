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
                .onAppear { delegate.model = model }
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
    weak var model: LinkModel?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
            if granted { DispatchQueue.main.async { application.registerForRemoteNotifications() } }
        }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in model?.registerPush(token: deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {}

    /// The relay's push says "an item is waiting"; tapping it opens the app, which fetches and copies it.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [] // In the foreground the receiver loop already picked it up.
    }

    /// Keeps receiving for ~25 s after leaving the app so a clip copied right then still lands.
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
