package kr.lit.api;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** 틀에 속한 회차를 기존 수정 API로 독립 변경하지 못하게 합니다. 호출자는 먼저 평가 소유권을 확인합니다. */
final class ClassSnapshots {
    private ClassSnapshots() {}
    static void requireStandalone(JdbcTemplate db,String id) {
        if(db.queryForObject("SELECT assessment_id FROM evaluations WHERE id=?",String.class,id)!=null)
            throw new ApiError.Failure(HttpStatus.CONFLICT,"CLASS_CONFIGURATION_MANAGED","반 회차의 공통 설정은 수행평가 틀에서 수정해주세요.");
    }
}
