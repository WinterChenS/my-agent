import { isImageArtifact, type ArtifactInfo } from "@/api/artifacts";

/**
 * 正文里 Markdown 图片的解析规则。
 *
 * <p>为什么按**文件名**匹配：官方 `deliver_artifact` 只回一句文字、不含 URL，模型无从得知
 * `/api/artifacts/{id}/download`，所以"模型写文件名、平台去产物里找"是唯一可行的约定；
 * 因此也**不新增** URL 协议、不改官方工具的返回值。
 */
export type ResolvedMarkdownImage =
  | { kind: "direct"; url: string }
  | { kind: "artifact"; item: ArtifactInfo };

export function resolveMarkdownImage(
  src: string,
  artifacts: ArtifactInfo[],
): ResolvedMarkdownImage | null {
  if (!src) {
    return null;
  }
  if (/^(data:|blob:|https?:)/i.test(src)) {
    return { kind: "direct", url: src };
  }
  const name = src.split(/[\\/]/).pop()?.toLowerCase() ?? "";
  if (!name) {
    return null;
  }
  const hit = artifacts.find((a) => isImageArtifact(a) && a.fileName.toLowerCase() === name);
  return hit ? { kind: "artifact", item: hit } : null;
}
