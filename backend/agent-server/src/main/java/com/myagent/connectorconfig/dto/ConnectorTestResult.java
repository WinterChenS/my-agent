package com.myagent.connectorconfig.dto;

import java.util.List;

/**
 * 「测试连接」的结果。失败时 {@code message} 要是**人话**（"IMAP 登录失败：授权码错误"），
 * 而不是异常类名——用户看不懂 {@code AuthenticationFailedException}。
 */
public record ConnectorTestResult(boolean ok, String message, List<String> steps) {}
