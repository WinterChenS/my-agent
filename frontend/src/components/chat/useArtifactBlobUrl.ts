import { useEffect, useState } from "react";
import { fetchArtifactBlobUrl, isImageArtifact, type ArtifactInfo } from "@/api/artifacts";

/**
 * 产物 → 可用于 `<img src>` / `<iframe src>` 的 blob URL。
 *
 * <p>产物下载接口要带 `Authorization`，所以不能把 URL 直接塞给 `<img>`；统一走
 * {@link fetchArtifactBlobUrl}（fetch → blob URL）。**只有这一处**做这件事：
 * 气泡内图片、产物缩略图、正文 Markdown 图片、预览面板里的图片与 PDF。
 * 切换/卸载时自动 `revokeObjectURL`。
 *
 * @param options.anyMime 默认只取图片（其它类型没必要下载字节）；PDF 等需要字节的类型传 true。
 */
export function useArtifactBlobUrl(
  item: ArtifactInfo | null | undefined,
  options?: { anyMime?: boolean },
) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const id = item?.id ?? null;
  const mime = item?.mime ?? null;
  const anyMime = options?.anyMime ?? false;
  // 依赖项用「要不要下载」这个布尔，而不是 item 对象本身（对象每次渲染都是新引用）
  const enabled = id != null && (anyMime || isImageArtifact(item));

  useEffect(() => {
    setUrl(null);
    setFailed(false);
    if (id == null || !enabled) {
      return;
    }
    let cancelled = false;
    let created: string | null = null;
    fetchArtifactBlobUrl(id, mime)
      .then((next) => {
        created = next;
        if (cancelled) {
          URL.revokeObjectURL(next);
        } else {
          setUrl(next);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setFailed(true);
        }
      });
    return () => {
      cancelled = true;
      if (created) {
        URL.revokeObjectURL(created);
      }
    };
  }, [id, enabled, mime]);

  return { url, failed };
}
