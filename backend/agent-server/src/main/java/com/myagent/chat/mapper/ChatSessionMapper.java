package com.myagent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myagent.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

/** chat_session 数据访问。 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {}
