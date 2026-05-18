package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.UserMemoryVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetUserMemoriesResponse {
    private UserMemoryVO[] memories;
}
