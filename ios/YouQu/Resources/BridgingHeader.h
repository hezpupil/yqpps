#ifndef YouQuBridgingHeader_h
#define YouQuBridgingHeader_h

/*
 * 仅用于把系统框架 CommonCrypto 暴露给 Swift。
 * ImageDecryptor.swift 用 CCCrypt 做 AES-128-ECB / PKCS7 解密，
 * 不引入任何第三方库；此头文件由 project.yml 的
 * SWIFT_OBJC_BRIDGING_HEADER 指向。
 */
#import <CommonCrypto/CommonCrypto.h>

#endif /* YouQuBridgingHeader_h */
