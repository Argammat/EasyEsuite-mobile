import SwiftUI

@main
struct EasyEsuiteApp: App {
    @StateObject private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(container)
                .tint(Brand.greenDeep)
        }
    }
}

enum Brand {
    // Brand colours lifted from the web app's "Modern" theme (erp.easyesuite.com, Oct 2026):
    // green primary, dark-green text on a light-green tint for active states, blue only for links/UPCs.
    static let green = Color(red: 0x2D / 255, green: 0xB6 / 255, blue: 0x82 / 255)      // brand green (accents, success)
    static let greenDeep = Color(red: 0x0D / 255, green: 0x7A / 255, blue: 0x51 / 255)  // filled buttons — readable with white text
    static let greenText = Color(red: 0x00 / 255, green: 0x69 / 255, blue: 0x47 / 255)  // active nav / chip text
    static let greenTint = Color(red: 0xDA / 255, green: 0xF2 / 255, blue: 0xE8 / 255)  // active nav / chip background
    static let blue = Color(red: 0x01 / 255, green: 0x71 / 255, blue: 0xE3 / 255)       // links, UPCs, data
    static let amber = Color(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255)
    static let red = Color(red: 0xCB / 255, green: 0x20 / 255, blue: 0x26 / 255)
    static let ink = Color(red: 0x0F / 255, green: 0x17 / 255, blue: 0x2A / 255)        // headings
    static let navy = Color(red: 0x0A / 255, green: 0x14 / 255, blue: 0x26 / 255)       // login brand panel
    static let page = Color(red: 0xFB / 255, green: 0xFB / 255, blue: 0xFB / 255)       // page background
    static let field = Color(red: 0xE8 / 255, green: 0xF0 / 255, blue: 0xFE / 255)      // inputs
    static let muted = Color(red: 0xEE / 255, green: 0xF1 / 255, blue: 0xF2 / 255)      // chips, muted panels
}
