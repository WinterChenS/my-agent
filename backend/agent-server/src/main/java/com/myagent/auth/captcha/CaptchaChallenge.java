package com.myagent.auth.captcha;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** captcha_challenge 表实体：一次登录用的滑动验证题目。 */
@TableName("captcha_challenge")
public class CaptchaChallenge {

    @TableId
    private String id;

    /** 拼图正确位置的左上角 x（原图像素坐标） */
    private Integer answerX;

    /** 拼图纵向位置（前端不需要猜，直接告诉它） */
    private Integer pieceY;

    /** 位置已验证通过 */
    private Boolean verified;

    /** 验证通过后发给前端的一次性票据 */
    private String ticket;

    private Integer attempts;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    /** 会话/客户端绑定：题目只能被同一个 sessionKey 验证并消费（防跨会话盗用 ticket） */
    private String sessionKey;

    /** 已被消费（成功验证或超过尝试次数）后不可再用 —— SQL 层原子抢占 */
    private Boolean used;

    private LocalDateTime consumedAt;

    /** 行为风险分（0-100），只用于记录/二次判断，不单独作为放行依据 */
    private Integer riskScore;

    /** SLIDER（拼图拖动）/ TEXT（无障碍替代题：算术） */
    private String mode;

    /** TEXT 模式的正确答案（与服务端坐标一样，只存库） */
    private String textAnswer;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getAnswerX() {
        return answerX;
    }

    public void setAnswerX(Integer answerX) {
        this.answerX = answerX;
    }

    public Integer getPieceY() {
        return pieceY;
    }

    public void setPieceY(Integer pieceY) {
        this.pieceY = pieceY;
    }

    public Boolean getVerified() {
        return verified;
    }

    public void setVerified(Boolean verified) {
        this.verified = verified;
    }

    public String getTicket() {
        return ticket;
    }

    public void setTicket(String ticket) {
        this.ticket = ticket;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public Boolean getUsed() {
        return used;
    }

    public void setUsed(Boolean used) {
        this.used = used;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getTextAnswer() {
        return textAnswer;
    }

    public void setTextAnswer(String textAnswer) {
        this.textAnswer = textAnswer;
    }
}
