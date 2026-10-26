/**
 * 「正在思考」的轻量提示（**不是气泡**）。
 *
 * <p>为什么不用气泡：用户实测指出——先放一个空气泡占位、后面思考/工具卡插在它下面，
 * 接着正文又补进**上面**那条气泡里，视觉顺序完全错乱；而且一个空的气泡本身也很怪。
 * 现在改成一行浅色提示（三点动画 + 文案），不参与消息流的位置关系，
 * 真实内容一到就整行消失（由 ChatPage 的条件控制）。
 *
 * <p>这样做还有个好处：正文永远从"当前末尾"开始渲染，不会被一个提前插入的气泡抢走位置。
 */
export function PendingIndicator({ label = "正在思考…" }: { label?: string }) {
  return (
    <div
      className="text-muted-foreground flex items-center gap-2 py-1 text-xs"
      data-testid="pending-bubble"
      role="status"
      aria-live="polite"
    >
      <span className="flex items-center gap-1" aria-hidden>
        <span className="bg-muted-foreground/50 size-1.5 animate-bounce rounded-full [animation-delay:-0.3s]" />
        <span className="bg-muted-foreground/50 size-1.5 animate-bounce rounded-full [animation-delay:-0.15s]" />
        <span className="bg-muted-foreground/50 size-1.5 animate-bounce rounded-full" />
      </span>
      <span>{label}</span>
    </div>
  );
}

/** 兼容旧名（已有引用/测试仍可用）。 */
export const PendingBubble = PendingIndicator;
