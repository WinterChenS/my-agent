
// 打包时由 scripts/build-extension.py 用 --platform-url 覆盖（内测包开箱即用）。
// 用户仍可在「连接设置」里改成别的地址；这里只是默认值。
export const DEFAULT_PLATFORM_URL = 'http://192.168.3.10:18080';

// 固定扩展 ID（manifest.key 决定）。网页靠它做「一键连接」的 externally_connectable 检测。
export const EXTENSION_ID = 'ijklemfakolbnlbgephimnjlaejjkmpp';
