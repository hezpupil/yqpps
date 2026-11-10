import SwiftUI

@main
struct YouQuApp: App {
    init() {
        // 启动本机 HTTP 代理（NWListener）：AVPlayer 的 KEY / 分片与加密图片都经它代取。
        // 端口就绪后 UI 即可通过 LocalProxyServer.shared.imageURL / m3u8URL 拼出地址。
        LocalProxyServer.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
