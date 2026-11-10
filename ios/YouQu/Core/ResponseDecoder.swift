import Foundation

enum ResponseError: Error, LocalizedError {
    case invalidBase64
    case invalidJSON

    var errorDescription: String? {
        switch self {
        case .invalidBase64: return "响应体不是合法的倒序 Base64"
        case .invalidJSON: return "响应解码后不是合法 JSON"
        }
    }
}

/// 上游解码后的完整响应：{"code":1,"message":"","data":{...}}
struct DecodedResponse {
    let code: Int
    let message: String
    let raw: String
    let root: [String: Any]

    /// code==1 成功（兼容 0 / 200）
    var success: Bool { code == 1 || code == 0 || code == 200 }

    /// 与 PHP Auth::isAuthExpired 一致
    var authExpired: Bool {
        if [401, 403, 4001, 4010].contains(code) { return true }
        let m = message.lowercased()
        return ["token", "登录", "login", "auth"].contains { m.contains($0) }
    }

    var dataDict: [String: Any]? { root["data"] as? [String: Any] }

    /// 取 data 下的字段；兼容 data.data.xxx 嵌套形式（show 接口偶尔出现）
    func value(_ key: String) -> Any? {
        guard let d = dataDict else { return nil }
        if let v = d[key] { return v }
        if let nested = d["data"] as? [String: Any] { return nested[key] }
        return nil
    }

    func string(_ key: String) -> String { DecodedResponse.stringValue(value(key)) }
    func int(_ key: String) -> Int? { DecodedResponse.intValue(value(key)) }
    func array(_ key: String) -> [Any] { (value(key) as? [Any]) ?? [] }
    func dict(_ key: String) -> [String: Any]? { value(key) as? [String: Any] }

    static func stringValue(_ any: Any?) -> String {
        if let s = any as? String { return s }
        if let n = any as? NSNumber { return n.stringValue }
        if let i = any as? Int { return String(i) }
        if let d = any as? Double { return String(Int(d)) }
        return ""
    }

    static func intValue(_ any: Any?) -> Int? {
        if let i = any as? Int { return i }
        if let n = any as? NSNumber { return n.intValue }
        if let s = any as? String { return Int(s) }
        if let d = any as? Double { return Int(d) }
        return nil
    }
}

/// 响应体是「倒序 Base64」：整串反转 → Base64 解码 → UTF-8 JSON。
enum ResponseDecoder {
    static func decode(_ body: Data) throws -> DecodedResponse {
        let trimmed = (String(data: body, encoding: .utf8) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let reversed = String(trimmed.reversed())

        var jsonData = Data(base64Encoded: reversed, options: .ignoreUnknownCharacters)
        if jsonData == nil || jsonData?.isEmpty == true {
            // 宽松重试：去掉可能的空白 / 换行
            let clean = reversed.filter { !$0.isWhitespace }
            jsonData = Data(base64Encoded: clean, options: .ignoreUnknownCharacters)
        }
        guard let jd = jsonData, !jd.isEmpty else { throw ResponseError.invalidBase64 }
        guard let obj = try? JSONSerialization.jsonObject(with: jd),
              let root = obj as? [String: Any] else { throw ResponseError.invalidJSON }

        return DecodedResponse(
            code: DecodedResponse.intValue(root["code"]) ?? -1,
            message: DecodedResponse.stringValue(root["message"]),
            raw: trimmed,
            root: root
        )
    }
}
