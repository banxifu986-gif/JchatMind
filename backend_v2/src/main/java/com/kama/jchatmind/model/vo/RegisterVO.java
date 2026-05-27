package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RegisterVO {
    private Long userId;
    private String token;
}
