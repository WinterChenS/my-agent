import { useEffect, useState } from "react";
import { fetchArtifactBytes, type ArtifactInfo } from "@/api/artifacts";

/**
 * 产物 → 原始字节（Excel / Word 这类要自己解的格式用）。
 *
 * <p>和 {@link useArtifactBlobUrl} 一样，唯一目的是**带上 Authorization 去取字节**；
 * 区别是这里给的是 `Uint8Array`，交给 `xlsx` / 自研 zip 解析用。
 * 传 `null` 表示这次不需要（避免无谓下载）。
 */
export function useArtifactBytes(item: ArtifactInfo | null | undefined) {
  const [bytes, setBytes] = useState<Uint8Array | null>(null);
  const [failed, setFailed] = useState(false);
  const id = item?.id ?? null;

  useEffect(() => {
    setBytes(null);
    setFailed(false);
    if (id == null) {
      return;
    }
    let cancelled = false;
    fetchArtifactBytes(id)
      .then((next) => {
        if (!cancelled) {
          setBytes(next);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setFailed(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  return { bytes, failed };
}
