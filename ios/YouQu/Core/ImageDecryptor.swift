import Foundation

/// 封面图解密（等价于 PHP 的 image.php）。
///
/// 上游返回：base64( AES-128-ECB( key="976f97d638360cde", PKCS7 ) 加密的字节 )
/// 解密后是字符串 "data:<mime>;base64,<图片字节的base64>"
/// 再解一层 base64 得到真实 JPEG/PNG 字节。
///
/// AES 用系统框架 CommonCrypto 的 CCCrypt（经 BridgingHeader.h 暴露），不引第三方库。
enum ImageDecryptor {
    struct Image {
        let mime: String
        let bytes: Data
    }

    static func decrypt(_ rawBody: Data) -> Image? {
        guard let text = String(data: rawBody, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }

        // 1) 响应体本身是 base64 文本
        guard let cipher = Data(base64Encoded: text, options: .ignoreUnknownCharacters),
              !cipher.isEmpty else { return nil }

        // 2) AES-128-ECB / PKCS7
        guard let plain = aes128ECBDecrypt(cipher),
              let plainText = String(data: plain, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }

        // 3) data URI
        guard plainText.hasPrefix("data:"), let comma = plainText.firstIndex(of: ",") else { return nil }
        let metaStart = plainText.index(plainText.startIndex, offsetBy: 5)
        let meta = String(plainText[metaStart..<comma]) // e.g. "image/jpeg;base64"
        let mimeRaw = meta.split(separator: ";").first.map(String.init) ?? "application/octet-stream"
        let mime = isValidMIME(mimeRaw) ? mimeRaw : "application/octet-stream"
        let b64 = String(plainText[plainText.index(after: comma)...])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let bytes = Data(base64Encoded: b64, options: .ignoreUnknownCharacters),
              !bytes.isEmpty else { return nil }
        return Image(mime: mime, bytes: bytes)
    }

    private static func isValidMIME(_ s: String) -> Bool {
        let parts = s.split(separator: "/")
        guard parts.count == 2 else { return false }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: ".-+"))
        return parts.allSatisfy { part in
            !part.isEmpty && part.unicodeScalars.allSatisfy { allowed.contains($0) }
        }
    }

    private static func aes128ECBDecrypt(_ data: Data) -> Data? {
        let keyData = DirectConfig.imageAESKey.data(using: .ascii)! // 16 字节 ASCII
        let keyCount = keyData.count
        let inCount = data.count
        var outData = Data(count: inCount + kCCBlockSizeAES128)
        let outCapacity = outData.count
        var outLength: Int = 0

        let status: CCCryptorStatus = keyData.withUnsafeBytes { keyRaw -> CCCryptorStatus in
            data.withUnsafeBytes { inRaw -> CCCryptorStatus in
                outData.withUnsafeMutableBytes { outRaw -> CCCryptorStatus in
                    CCCrypt(
                        CCOperation(kCCDecrypt),
                        CCAlgorithm(kCCAlgorithmAES128),
                        CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding),
                        keyRaw.baseAddress, keyCount,
                        nil,                       // ECB 无 IV
                        inRaw.baseAddress, inCount,
                        outRaw.baseAddress, outCapacity,
                        &outLength
                    )
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        return Data(outData.prefix(outLength))
    }
}

// MARK: - 图片缓存（内存 + 磁盘）

final class CachedImage: NSObject {
    let mime: String
    let bytes: Data
    init(mime: String, bytes: Data) {
        self.mime = mime
        self.bytes = bytes
    }
}

final class ImageCache {
    static let shared = ImageCache()

    private let mem = NSCache<NSString, CachedImage>()
    private let dir: URL

    init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        dir = caches.appendingPathComponent("YouQuImages", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        mem.countLimit = 200
    }

    func get(_ key: String) -> CachedImage? {
        if let c = mem.object(forKey: key as NSString) { return c }
        do {
            let data = try Data(contentsOf: file(key))
            guard let plist = try PropertyListSerialization.propertyList(
                    from: data, options: [], format: nil) as? [String: Any],
                  let mime = plist["mime"] as? String,
                  let bytes = plist["bytes"] as? Data else { return nil }
            let c = CachedImage(mime: mime, bytes: bytes)
            mem.setObject(c, forKey: key as NSString)
            return c
        } catch {
            return nil
        }
    }

    func set(_ image: CachedImage, forKey key: String) {
        mem.setObject(image, forKey: key as NSString)
        let plist: [String: Any] = ["mime": image.mime, "bytes": image.bytes]
        if let data = try? PropertyListSerialization.data(
            fromPropertyList: plist, format: .binary, options: 0) {
            try? data.write(to: file(key), options: .atomic)
        }
    }

    private func file(_ key: String) -> URL {
        let name = Data(key.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "=", with: "")
        return dir.appendingPathComponent(name + ".cache")
    }
}
