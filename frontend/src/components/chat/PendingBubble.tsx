/**
 * 「正在思考」占位气泡。
 *
 * <p>为什么需要它：模型在首字之前可能要想很久（推理型模型尤其明显），
 * 这期间一条 assistant 消息都还没产生 —— 界面上只有用户自己的气泡，看着像"没反应"。
 * 所以在这一轮开始后先放一个占位气泡，等真实内容到了它自然消失（由 ChatPage 的条件控制）。
 *
 * <p>样式刻意与 {@link MessageBubble} 的助手气泡保持一致（左对齐、卡片底 + 细边框、同圆角），
 * 这样"占位 → 真内容"的切换不会跳变。
 */
export function PendingBubble({ label = "正在思考…" }: { label?: string }) {
  return (
    <div className="flex w-full justify-start" data-testid="pending-bubble">
      <div className="bg-card text-card-foreground flex max-w-[min(48rem,85%)] items-center gap-2 rounded-2xl rounded-bl-md border px-4 py-2.5 text-sm shadow-sm">
        <span className="flex items-center gap-1" aria-hidden>
          <span className="bg-muted-foreground/60 size-1.5 animate-bounce rounded-full [animation-delay:-0.3s]" />
          <span className="bg-muted-foreground/60 size-1.5 animate-bounce rounded-full [animation-delay:-0.15s]" />
          <span className="bg-muted-foreground/60 size-1.5 animate-bounce rounded-full" />
        </span>
        <span className="text-muted-foreground text-xs">{label}</span>
      </div>
    </div>
  );
}
