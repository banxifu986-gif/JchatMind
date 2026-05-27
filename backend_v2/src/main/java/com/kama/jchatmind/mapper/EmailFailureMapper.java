package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.EmailSendFailure;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmailFailureMapper {

    int insert(EmailSendFailure failure);
}
