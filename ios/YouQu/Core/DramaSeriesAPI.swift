import Foundation

// MARK: - 短剧专区数据模型（与影视 VOD 完全隔离）

/// 短剧列表卡片：/java/drama/lists、/java/drama/search 的 data.list 元素
struct SeriesItem: Decodable, Identifiable, Hashable {
    /// 上游 id 是数字/字符串漂移，统一转字符串作为 Identifiable
    let id: String
    let title: String
    let cover: String
    let epText: String
    let intro: String

    enum CodingKeys: String, CodingKey {
        case id, title, cover, intro
        case epText = "ep_text"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // id 兼容数字 / 字符串
        if let s = try? c.decode(String.self, forKey: .id) {
            id = s
        } else if let i = try? c.decode(Int64.self, forKey: .id) {
            id = String(i)
        } else {
            id = ""
        }
        title = (try? c.decode(String.self, forKey: .title)) ?? ""
        cover = (try? c.decode(String.self, forKey: .cover)) ?? ""
        epText = (try? c.decode(String.self, forKey: .epText)) ?? ""
        intro = (try? c.decode(String.self, forKey: .intro)) ?? ""
    }

    /// JSONSerialization 手动构造（decodeModel 之外的兜底）
    init(id: String, title: String, cover: String, epText: String, intro: String) {
        self.id = id; self.title = title; self.cover = cover
        self.epText = epText; self.intro = intro
    }
}

/// 短剧列表页：data.page / data.more / data.list
struct SeriesPage {
    let list: [SeriesItem]
    let page: Int
    let more: Bool
}

/// 短剧剧集：data.eps[] 元素，src 为带一次性签名的 m3u8 绝对地址
struct SeriesEpisode: Identifiable, Hashable {
    let id: String
    let epNo: String
    let title: String
    let src: String
}

/// 短剧详情 data.drama
struct SeriesInfo {
    let id: String
    let title: String
    let cover: String
    let intro: String
    let epText: String
}

/// 短剧详情响应
struct SeriesDetail {
    let drama: SeriesInfo
    let eps: [SeriesEpisode]
}

// MARK: - 短剧业务接口（java drama 系列接口，与影视 DramaAPI 完全隔离）

final class DramaSeriesAPI {
    static let shared = DramaSeriesAPI()
    private let upstream = UpstreamClient.shared

    /// GET /java/drama/lists?page= → data.list / data.page / data.more
    func lists(page: Int) async throws -> SeriesPage {
        let r = try await upstream.get(path: "/java/drama/lists", query: ["page": String(page)])
        return pageResult(from: r, requestPage: page)
    }

    /// GET /java/drama/hotWords → data.list: [String]（与影视热词 {id,word} 不同）
    func hotWords() async throws -> [String] {
        let r = try await upstream.get(path: "/java/drama/hotWords")
        return r.array("list").compactMap { $0 as? String }.filter { !$0.isEmpty }
    }

    /// POST /java/drama/search（kw/page/spm=drama.restore）
    func search(kw: String, page: Int) async throws -> SeriesPage {
        let r = try await upstream.postMultipart(
            path: "/java/drama/search",
            fields: [("kw", kw), ("page", String(page)), ("spm", "drama.restore")]
        )
        return pageResult(from: r, requestPage: page)
    }

    /// POST /java/drama/detail（id，需 token）→ data.drama + data.eps
    func detail(id: String) async throws -> SeriesDetail {
        let r = try await upstream.postMultipart(
            path: "/java/drama/detail",
            fields: [("id", id)],
            needAuth: true
        )
        let d = r.dict("drama") ?? [:]
        let info = SeriesInfo(
            id: DecodedResponse.stringValue(d["id"]),
            title: DecodedResponse.stringValue(d["title"]),
            cover: DecodedResponse.stringValue(d["cover"]),
            intro: DecodedResponse.stringValue(d["intro"]),
            epText: DecodedResponse.stringValue(d["ep_text"])
        )
        let eps: [SeriesEpisode] = r.array("eps").compactMap { raw in
            guard let e = raw as? [String: Any] else { return nil }
            let no = DecodedResponse.stringValue(e["ep_no"])
            return SeriesEpisode(
                id: DecodedResponse.stringValue(e["id"]) + "-" + no,
                epNo: no,
                title: DecodedResponse.stringValue(e["title"]),
                src: DecodedResponse.stringValue(e["src"])
            )
        }
        return SeriesDetail(drama: info, eps: eps)
    }

    /// data.list / data.page / data.more 统一解析，page 用请求值兜底
    private func pageResult(from r: DecodedResponse, requestPage: Int) -> SeriesPage {
        let list: [SeriesItem] = r.array("list").compactMap { raw in
            guard let o = raw as? [String: Any] else { return nil }
            return SeriesItem(
                id: DecodedResponse.stringValue(o["id"]),
                title: DecodedResponse.stringValue(o["title"]),
                cover: DecodedResponse.stringValue(o["cover"]),
                epText: DecodedResponse.stringValue(o["ep_text"]),
                intro: DecodedResponse.stringValue(o["intro"])
            )
        }
        let p = r.int("page").map { max(1, $0) } ?? requestPage
        let moreRaw = r.root["data"] as? [String: Any]
        let more = (moreRaw?["more"] as? Bool == true)
            || DecodedResponse.intValue(moreRaw?["more"]) == 1
        return SeriesPage(list: list, page: p, more: more)
    }
}
