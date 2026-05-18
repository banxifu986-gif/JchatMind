package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.UserMemoryCandidateVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetUserMemoryCandidatesResponse {
    private UserMemoryCandidateVO[] candidates;
}
