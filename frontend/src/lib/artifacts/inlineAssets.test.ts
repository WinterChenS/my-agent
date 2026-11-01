import { describe, expect, it } from "vitest";
import {
  basenameOf,
  collectRelativeRefs,
  guessMime,
  isRelativeRef,
  replaceRefs,
  toDataUri,
} from "./inlineAssets";

describe("inlineAssets：HTML 预览的同会话资源内联", () => {
  it("只收相对引用，跳过 http/data/#/协议相对", () => {
    const html = `
      <script src="assets/vendor/gsap.min.js"></script>
      <link href='assets/theme.css' rel="stylesheet">
      <img src="https://cdn.example.com/a.png">
      <img src="data:image/png;base64,AAA">
      <a href="#slide-2">跳转</a>
      <img src="//cdn.example.com/b.png">
      <style>@font-face{src:url("assets/fonts/x.woff2") format("woff2")}</style>
    `;
    expect(collectRelativeRefs(html)).toEqual([
      "assets/vendor/gsap.min.js",
      "assets/theme.css",
      "assets/fonts/x.woff2",
    ]);
  });

  it("相对引用判定：常见自带来源全部排除", () => {
    for (const bad of ["#a", "//x/y", "https://a/b", "data:x", "blob:x", "mailto:a", "javascript:x"]) {
      expect(isRelativeRef(bad)).toBe(false);
    }
    expect(isRelativeRef("assets/a.png")).toBe(true);
    expect(isRelativeRef("./a.png")).toBe(true);
  });

  it("产物只存文件名：引用按 basename 匹配", () => {
    expect(basenameOf("assets/vendor/gsap.min.js")).toBe("gsap.min.js");
    expect(basenameOf("./fonts/x.woff2?v=2#a")).toBe("x.woff2");
    expect(basenameOf("index.html")).toBe("index.html");
  });

  it("替换是精确原文替换（不会误伤同名文本）", () => {
    const html = '<img src="a.png"><p>a.png</p>';
    const out = replaceRefs(html, new Map([["a.png", "data:image/png;base64,QQ=="]]));
    expect(out).toBe('<img src="data:image/png;base64,QQ=="><p>a.png</p>');
  });

  it("MIME 猜测与 data URI 生成", () => {
    expect(guessMime("x.woff2")).toBe("font/woff2");
    expect(guessMime("gsap.min.js")).toBe("text/javascript");
    expect(guessMime("bad.unknown")).toBe("application/octet-stream");
    expect(toDataUri(new Uint8Array([65, 66]), "text/plain")).toBe("data:text/plain;base64,QUI=");
  });
});
