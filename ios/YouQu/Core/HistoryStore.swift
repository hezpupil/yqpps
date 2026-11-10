import Foundation

/// 一条播放记录。与 Android 端 play_history 保持同样的字段含义。
struct PlayRecord: Codable, Identifiable, Hashable {
    var id: String { vid }
    let vid: String
    let title: String
    let image: String
    let time: TimeInterval

    var dateText: String {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f.string(from: Date(timeIntervalSince1970: time))
    }
}

/// 搜索记录 / 播放记录的本地持久化（UserDefaults + JSON，无数据库）。
/// 与 Android 的 SettingsRepository 对齐：最新在前、同 id 去重、上限 20 条。
final class HistoryStore {
    static let shared = HistoryStore()

    private let defaults = UserDefaults.standard
    private let searchKey = "yq_search_history"
    private let playKey = "yq_play_history"
    private let limit = 20

    private init() {}

    // MARK: - 搜索记录

    func searches() -> [String] {
        (defaults.array(forKey: searchKey) as? [String])?.filter { !$0.isEmpty } ?? []
    }

    func addSearch(_ keyword: String) {
        let kw = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !kw.isEmpty else { return }
        let next = [kw] + searches().filter { $0 != kw }
        defaults.set(Array(next.prefix(limit)), forKey: searchKey)
    }

    func clearSearches() {
        defaults.removeObject(forKey: searchKey)
    }

    // MARK: - 播放记录

    func plays() -> [PlayRecord] {
        guard let data = defaults.data(forKey: playKey),
              let list = try? JSONDecoder().decode([PlayRecord].self, from: data) else {
            return []
        }
        return list
    }

    func addPlay(vid: String, title: String, image: String = "") {
        guard !vid.isEmpty, !title.isEmpty else { return }
        let record = PlayRecord(
            vid: vid,
            title: title,
            image: image,
            time: Date().timeIntervalSince1970
        )
        let next = [record] + plays().filter { $0.vid != vid }
        if let data = try? JSONEncoder().encode(Array(next.prefix(limit))) {
            defaults.set(data, forKey: playKey)
        }
    }

    func clearPlays() {
        defaults.removeObject(forKey: playKey)
    }
}
