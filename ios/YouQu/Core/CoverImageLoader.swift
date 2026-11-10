import Foundation
import UIKit

/// 列表封面的懒加载器（替代 SwiftUI 自带的 AsyncImage）。
///
/// AsyncImage 只按「视图出现」发起请求，且用 URLSession.shared 无并发上限，
/// 快速滑动时会瞬间堆出十几个回源请求。这里改为由 PosterImage 的
/// onAppear / onDisappear 显式驱动：出现后防抖、离屏即 cancel，
/// 再加内存缓存 + URLCache 双层，回列表时不再重复下载。
final class CoverImageLoader {
    static let shared = CoverImageLoader()

    private let memory = NSCache<NSString, UIImage>()

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        config.requestCachePolicy = .returnCacheDataElseLoad
        // 图片只走本机 /img 代理，限制本机连接数，剩余连接留给播放链路
        config.httpMaximumConnectionsPerHost = 2
        config.urlCache = URLCache(memoryCapacity: 16 * 1024 * 1024,
                                   diskCapacity: 64 * 1024 * 1024,
                                   diskPath: "youqu-covers")
        return URLSession(configuration: config)
    }()

    private init() {
        memory.countLimit = 120
        memory.totalCostLimit = 24 * 1024 * 1024
    }

    /// 同步命中缓存，命中即可直接出图，连防抖都不用等。
    func cached(_ key: String) -> UIImage? {
        memory.object(forKey: key as NSString)
    }

    /// 由视图的 Task 调用；视图离屏时 Task 被取消，URLSession 请求随之取消。
    func load(_ urlString: String) async -> UIImage? {
        guard let url = URL(string: urlString) else { return nil }
        let key = urlString as NSString
        if let hit = memory.object(forKey: key) { return hit }
        do {
            let (data, response) = try await session.data(from: url)
            if let http = response as? HTTPURLResponse,
               !(200..<300).contains(http.statusCode) { return nil }
            guard !Task.isCancelled, let image = UIImage(data: data) else { return nil }
            memory.setObject(image, forKey: key, cost: data.count)
            return image
        } catch {
            return nil
        }
    }
}
