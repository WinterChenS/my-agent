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
}
