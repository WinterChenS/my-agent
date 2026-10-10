package com.myagent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/** chat_message 数据访问。 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {}
