import { useEffect, useState } from "react";
import { fetchArtifactBlobUrl, isImageArtifact, type ArtifactInfo } from "@/api/artifacts";

/**
 * 图片产物 → 可用于 `<img src>` 的 blob URL。
 *
 * <p>产物下载接口要带 `Authorization`，所以不能把 URL 直接塞给 `<img>`；统一走
 * {@link fetchArtifactBlobUrl}（fetch → blob URL）。**只有这一处**做这件事，四个地方复用：
 * 气泡内图片、产物缩略图、右侧抽屉、正文 Markdown 图片。切换/卸载时自动 `revokeObjectURL`。
 */
export function useArtifactBlobUrl(item: ArtifactInfo | null | undefined) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const id = item?.id ?? null;
  const image = isImageArtifact(item);

  useEffect(() => {
    setUrl(null);
    setFailed(false);
    if (id == null || !image) {
      return;
    }
    let cancelled = false;
    let created: string | null = null;
    fetchArtifactBlobUrl(id)
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
  }, [id, image]);

  return { url, failed };
}
