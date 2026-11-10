import Foundation
import Security
import UIKit

/// 门禁状态：云端 fail-closed，断网 / 服务器不可达一律拦截。
enum GateState {
    case checking
    case allowed
    case activation(message: String)   // 首次激活：展示设备码 + 输入激活码
    case blockedNetwork                 // 断网拦截
    case blockedDisabled(String)        // 停用 / 解绑拦截
}

// MARK: - Keychain（设备标识跨重装保持稳定）

private enum Keychain {
    static func string(forKey key: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: kCFBooleanTrue as Any,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ value: String, forKey key: String) {
        let data = Data(value.utf8)
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        let attrs: [String: Any] = [kSecValueData as String: data]
        if SecItemCopyMatching(q as CFDictionary, nil) == errSecSuccess {
            SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
        } else {
            var add = q
            add[kSecValueData as String] = data
            SecItemAdd(add as CFDictionary, nil)
        }
    }
}

/// 设备激活门禁（云端验证），对齐 Android 的 ActivationRepository。
///
/// 1. 取本机设备标识（identifierForVendor，Keychain 持久化），格式化为
///    「设备码」XXXX-XXXX-XXXX-XXXX 展示给用户。
/// 2. 用户把设备码发给管理员；管理员在后台生成 / 绑定激活码。
/// 3. 用户输入激活码 → App 把「设备码 + 激活码」POST 到云端验证接口，
///    App 本地不做任何加密比对（防本地破解）。
/// 4. 每次启动调用 status 核对；后台删除 / 停用则拦截。
/// 5. 断网 / 服务器不可达一律拦截（fail-closed），提示「无网络，连接后重开」。
final class ActivationService {
    static let shared = ActivationService()

    private let kDeviceUUID = "youqu.device.uuid"
    private let kActivated = "youqu.device.activated"
    private let defaults = UserDefaults.standard

    enum ActError: Error { case badURL, http, badJSON }

    enum VerifyResult {
        case success
        case failure(String)
    }

    // MARK: - 设备码

    /// 16 位大写 hex（无连字符），发往云端 / 本地比较均用此值
    func deviceCodeRaw() -> String {
        let hexChars = Set("0123456789ABCDEF")
        let uuid: String
        if let saved = Keychain.string(forKey: kDeviceUUID) {
            uuid = saved
        } else {
            let fresh = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
            Keychain.set(fresh, forKey: kDeviceUUID)
            uuid = fresh
        }
        let hex = String(uuid.uppercased().filter { hexChars.contains($0) }
            .prefix(DirectConfig.deviceCodeLength))
        if hex.count == DirectConfig.deviceCodeLength,
           hex != String(repeating: "0", count: DirectConfig.deviceCodeLength) {
            return hex
        }
        return String(UUID().uuidString.uppercased().filter { hexChars.contains($0) }
            .prefix(DirectConfig.deviceCodeLength))
    }

    /// 展示用设备码 XXXX-XXXX-XXXX-XXXX
    func deviceCode() -> String {
        var out = ""
        for (i, ch) in deviceCodeRaw().enumerated() {
            if i > 0 && i % 4 == 0 { out.append("-") }
            out.append(ch)
        }
        return out
    }

    func markActivated() {
        defaults.set(true, forKey: kActivated)
        Keychain.set("1", forKey: kActivated)
    }

    private func everActivated() -> Bool {
        defaults.bool(forKey: kActivated) || Keychain.string(forKey: kActivated) == "1"
    }

    /// 与后台 act_normalize_code 保持一致：去连字符 / 空格、转大写、保留全部字母数字。
    /// 不能用 A-F 过滤（会删掉 G/K/M 等非 A-F 字母导致长度不足）。
    func normalize(_ s: String) -> String {
        String(s.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            .filter { $0.isLetter || $0.isNumber })
    }

    // MARK: - 云端动作

    /// 每次启动核对：ok 放行；被停用拦截；断网 / 不可达一律拦截。
    func checkStatus() async -> GateState {
        do {
            let json = try await post([
                "action": "status",
                "device": deviceCodeRaw()
            ])
            let ok = json["ok"] as? Bool ?? false
            if ok {
                markActivated()
                return .allowed
            }
            let rawMsg = (json["msg"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let msg = rawMsg.isEmpty ? "设备未激活或已被管理员停用" : rawMsg
            if looksDisabled(msg) || (everActivated() && msg.contains("停用")) {
                return .blockedDisabled(msg)
            }
            return .activation(message: msg)
        } catch {
            return .blockedNetwork
        }
    }

    /// 首次验证激活码。verify 动作不带 action 字段，只有 device + code。
    func verify(code: String) async -> VerifyResult {
        let normalized = normalize(code)
        guard normalized.count == DirectConfig.activationCodeLength else {
            return .failure("激活码格式不正确（12 位字母数字）")
        }
        do {
            let json = try await post([
                "device": deviceCodeRaw(),
                "code": normalized
            ])
            let ok = json["ok"] as? Bool ?? false
            if ok {
                markActivated()
                return .success
            }
            let msg = (json["msg"] as? String) ?? "验证失败"
            return .failure(msg.isEmpty ? "验证失败" : msg)
        } catch {
            return .failure("无法连接验证服务器，请检查网络后重试")
        }
    }

    /// 上报机型 / 系统 / App 版本，失败静默不影响界面流程。
    @discardableResult
    func report() async -> Bool {
        let model = UIDevice.current.model
        let os = "iOS " + UIDevice.current.systemVersion
        let app = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.0.23"
        let json = try? await post([
            "action": "report",
            "device": deviceCodeRaw(),
            "model": String(model.prefix(64)),
            "os": String(os.prefix(64)),
            "app": String(app.prefix(64))
        ])
        return (json?["ok"] as? Bool) ?? false
    }

    // MARK: - 内部

    private func looksDisabled(_ msg: String) -> Bool {
        let lower = msg.lowercased()
        let strong = ["已被停用", "已停用", "禁用", "封禁", "已删除", "已解绑", "被解绑",
                      "disabled", "banned", "revoked"]
        return strong.contains { lower.contains($0) }
    }

    /// POST 到 act/api.php，固定请求令牌放在 X-Act-Token 头。
    private func post(_ fields: [String: String]) async throws -> [String: Any] {
        guard let url = URL(string: DirectConfig.actVerifyURL) else { throw ActError.badURL }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue(DirectConfig.actToken, forHTTPHeaderField: "X-Act-Token")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = formBody(fields)
        req.timeoutInterval = 12

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw ActError.http
        }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw ActError.badJSON
        }
        return json
    }

    private func formBody(_ fields: [String: String]) -> Data {
        var comps = URLComponents()
        comps.queryItems = fields.map { URLQueryItem(name: $0.key, value: $0.value) }
        return Data((comps.percentEncodedQuery ?? "").utf8)
    }
}
