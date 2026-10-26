package com.myagent.agui.channel;

/**
 * IM 渠道的**媒体收发**能力（各家 IM 的下载/上传/发送协议不同，这里只统一语义）。
 *
 * <p>为什么需要这层抽象：官方 AgentScope 渠道模块只做「消息体」收发，媒体（图片/文件/语音）
 * 的下载与发送各家自己实现。没有抽象的话，网关里就会长出一串
 * {@code if (dingtalk) ... else if (feishu) ...}，第二家进来必然改坏第一家。
 *
 * <p>实现约定：
 * <ul>
 *   <li>实现类自己管理 token 缓存与 HTTP 客户端，**必须线程安全**；</li>
 *   <li>{@link #sendMedia} 只负责「发一个媒体」，正文另走官方 {@code Channel.deliver}；</li>
 *   <li>失败抛 {@link MediaException}（或用实现类自己的子类），调用方统一 WARN 留痕。</li>
 * </ul>
 */
public interface ImMediaClient {

    /**
     * 下载入站媒体。
     *
     * @param mediaId 渠道给的媒体标识（钉钉是 downloadCode，飞书是 image_key/file_key）
     * @return 文件字节
     */
    byte[] download(String mediaId);

    /**
     * 发送一个媒体消息（图片按图片发、其它按文件发，由实现类按扩展名判断）。
     *
     * @param direct true=单聊，false=群聊
     * @param peerId 接收方裸 id（单聊是用户 id，群聊是会话 id）；**不要**传复合地址串
     * @param fileName 文件名（决定图片/文件与扩展名）
     * @param bytes 内容
     */
    void sendMedia(boolean direct, String peerId, String fileName, byte[] bytes);

    /** 媒体收发的业务异常（父类型，便于调用方统一捕获）。 */
    class MediaException extends RuntimeException {

        public MediaException(String message) {
            super(message);
        }

        public MediaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
