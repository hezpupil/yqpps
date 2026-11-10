import Foundation

/// 上游 JSON 里 id / duration 等字段可能是数字也可能是字符串，统一按字符串容错解码。
private extension KeyedDecodingContainer {
    func flexibleString(_ key: Key) -> String {
        if let s = try? decode(String.self, forKey: key) { return s }
        if let i = try? decode(Int.self, forKey: key) { return String(i) }
        if let d = try? decode(Double.self, forKey: key) { return String(Int(d)) }
        if let b = try? decode(Bool.self, forKey: key) { return b ? "1" : "0" }
        return ""
    }
}

// MARK: - Codable 模型

struct WordItem: Decodable, Identifiable, Hashable {
    let id: String
    let word: String

    enum CodingKeys: String, CodingKey { case id, word }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.flexibleString(.id)
        word = c.flexibleString(.word)
    }
}

struct VideoItem: Decodable, Identifiable, Hashable {
    let id: String
    let title: String
    let image: String
    let duration: String
    let category: String

    enum CodingKeys: String, CodingKey { case id, title, image, duration, category }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.flexibleString(.id)
        title = c.flexibleString(.title)
        image = c.flexibleString(.image)
        duration = c.flexibleString(.duration)
        category = c.flexibleString(.category)
    }
}

struct VideoDetail: Decodable, Hashable {
    let id: String
    let title: String
    let src: String
    let duration: String
    let image: String

    enum CodingKeys: String, CodingKey { case id, title, src, duration, image }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.flexibleString(.id)
        title = c.flexibleString(.title)
        src = c.flexibleString(.src)
        duration = c.flexibleString(.duration)
        image = c.flexibleString(.image)
    }

    init(id: String, title: String, src: String, duration: String, image: String) {
        self.id = id
        self.title = title
        self.src = src
        self.duration = duration
        self.image = image
    }
}

struct ShowDetail {
    let video: VideoDetail
    let playcode: String
    let guess: [VideoItem]
}

struct SearchResult {
    let list: [VideoItem]
    let page: Int
    let pages: Int
}

// MARK: - 业务接口

final class DramaAPI {
    static let shared = DramaAPI()

    private let upstream = UpstreamClient.shared

    /// GET /java/index/index → data.hot: [{id, word}]
    func index() async throws -> [WordItem] {
        let r = try await upstream.get(path: "/java/index/index")
        return decodeList(WordItem.self, from: r.array("hot"))
    }

    /// GET /java/index/hot → data.list: [{id, word}]
    func hotWords() async throws -> [WordItem] {
        let r = try await upstream.get(path: "/java/index/hot")
        return decodeList(WordItem.self, from: r.array("list"))
    }

    /// GET /java/index/recent → data.list: [{id,title,image,duration,category}]
    func recent() async throws -> [VideoItem] {
        let r = try await upstream.get(path: "/java/index/recent")
        return decodeList(VideoItem.self, from: r.array("list"))
    }

    /// POST /java/so/title?page=&order=&t= 搜索
    /// order：1=最久，3=最热，9=最新（默认）
    func search(kw: String, page: Int = 1, order: Int = 9) async throws -> SearchResult {
        let t = String(Int(Date().timeIntervalSince1970 * 1000))
        let r = try await upstream.postMultipart(
            path: "/java/so/title",
            fields: [
                ("order", String(order)),
                ("page", String(page)),
                ("kw", kw),
                ("spm", "home.hot")
            ],
            query: ["page": String(page), "order": String(order), "t": t]
        )
        let list = decodeList(VideoItem.self, from: r.array("list"))
        // 上游若不回 page/pages，用请求值兜底，避免分页逻辑拿到 0
        let p = r.int("page").map { max(1, $0) } ?? page
        let pages = r.int("pages").map { max(1, $0) } ?? 1
        return SearchResult(list: list, page: p, pages: pages)
    }

    /// POST /java/show/{vid} 详情。兼容 data.data.video.src 嵌套形式。
    func show(vid: String) async throws -> ShowDetail {
        let r = try await upstream.postMultipart(
            path: "/java/show/\(vid)",
            fields: [("vid", vid), ("spm", "show.guess")],
            needAuth: true
        )
        var video = r.dict("video").flatMap { decodeModel(VideoDetail.self, $0) }
            ?? VideoDetail(id: vid, title: "", src: "", duration: "", image: "")
        if video.src.isEmpty {
            let alt = r.string("src")
            if !alt.isEmpty {
                video = VideoDetail(
                    id: video.id,
                    title: video.title,
                    src: alt,
                    duration: video.duration,
                    image: video.image
                )
            }
        }
        return ShowDetail(
            video: video,
            playcode: r.string("playcode"),
            guess: decodeList(VideoItem.self, from: r.array("guess"))
        )
    }

    /// POST /java/vod/playtime?c={playcode} 心跳（每 30 秒一次，seconds=3）
    @discardableResult
    func playtime(playcode: String, seconds: Int = 3) async -> Bool {
        guard !playcode.isEmpty else { return false }
        let r = try? await upstream.postMultipart(
            path: "/java/vod/playtime",
            fields: [("playcode", playcode), ("seconds", String(seconds))],
            query: ["c": playcode]
        )
        return r?.success ?? false
    }

    /// POST /java/device/active 设备激活
    @discardableResult
    func deviceActive() async -> Bool {
        let r = try? await upstream.postMultipart(
            path: "/java/device/active",
            fields: [],
            needAuth: true
        )
        return r?.success ?? false
    }

    // MARK: - 解码工具

    private func decodeList<T: Decodable>(_ type: T.Type, from array: [Any]) -> [T] {
        array.compactMap { decodeModel(T.self, $0) }
    }

    private func decodeModel<T: Decodable>(_ type: T.Type, _ object: Any) -> T? {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}
