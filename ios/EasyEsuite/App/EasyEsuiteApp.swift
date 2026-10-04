import SwiftUI

@main
struct EasyEsuiteApp: App {
    @StateObject private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(container)
                .tint(Brand.blue)
        }
    }
}

enum Brand {
    // Brand colours lifted from the web app.
    static let blue = Color(red: 0x01 / 255, green: 0x71 / 255, blue: 0xE3 / 255)
    static let green = Color(red: 0x2D / 255, green: 0xB6 / 255, blue: 0x82 / 255)
    static let amber = Color(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255)
    static let red = Color(red: 0xDC / 255, green: 0x26 / 255, blue: 0x26 / 255)
}
