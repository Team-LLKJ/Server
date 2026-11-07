# PDF·HWP 공통 학습 보고서 서식

feature/hwp-report-format 구현·미배포. feature/analysis-rubric-v2(d34f51f)를 기반으로 합니다. PR을 나누면 analysis-rubric-v2를 먼저 머지해야 합니다.

## 생성과 표현

`ReportLayout`이 저장된 UsageReport의 공통 표현 모델을 만들고 PDF·HWP 렌더러가 동일한 블록을 출력합니다. A4, 상하18mm/좌우16mm, 한글 본문11pt. 파란 LIT 문자 로고·제목·밑줄·출력일, 학생/제출상태/제출일/평가점수 정보 표와 다음 순서를 사용합니다.

1. 종합 평가: 저장·AI 요청·완료·실패·관측된 경고 수, 교사 의견·검토 상태
2. 평가 기준별 루브릭: 평가 기준/점수/평가 내용 표
3. AI 활용 분석: 저장된 분류별 요청 건수, 답안 기여도 미산출 안내
4. 활동 및 경고 기록: KST 시각·문항 순번·한글 활동명
5. 문항별 제출 답안: 순번·제목·문제·답안

includeConversations=true일 때만 '부록. AI 대화 기록'을 새 쪽에서 시작합니다. 기본값false일 때 질문·응답 원문 및 분류 근거 발췌를 출력하지 않습니다. 실제 장식형 로고 이미지 원본 대신 파란 LIT 문자 로고를 사용합니다.

점수는 스냅샷의 합계/만점(예:72/100점)이며 구버전 0~4 교사 채점은 실제 20점 만점을 유지합니다. 미채점·부분 채점의 총점은 '미채점'입니다. 임의로100점 환산하지 않습니다. 평가 내용이 비어 있으면 빈 셀만 남기고 '근거:[미입력]' 줄은 생략합니다. 긴 내용은 표에 요약하고 상세 문단에 전체를 보존합니다. 긴 답안은 표 밖에서 줄바꿈·페이지 분할합니다.

요청·문항·리포트·교사 UUID와 제출 확인 해시는 인쇄 본문에서 제외합니다. 사용자 답안·대화 원문 자체의 문자열을 일괄 삭제하지는 않습니다. 다운로드 파일명·JSON 식별자는 유지합니다. 생성일을 출력일로 사용해 같은 저장본의 PDF/HWP 시각을 일치시키며 `yyyy. MM. dd HH:mm (KST)`로 표시합니다. 영문 내부 코드 대신 한글 명칭을 출력합니다. 임시 기준 안내의 중복을 줄이고 '신고만으로 부정행위 판단 안 함', '기여율 미산출', '자동 분류는 교사 검토 필요' 안내는 유지합니다.

두 형식은 동일 데이터·블록·서식을 사용하지만 PDF와 한글 프로그램의 글꼴 대체·조판 차이로 줄바꿈과 페이지 수까지 동일함을 보장하지는 않습니다. HWP는 실제 표·글자/문단 스타일·페이지 나눔이 있는 HWP5 문서입니다. 한글 앱 최종 렌더링 확인은 별도 필요합니다.

## 경로와 호환

기존 /api/teacher/evaluations/{id}/students/{participantId}/report/pdf 및 /report.pdf, /report/hwp 및 /report.hwp 유지. 요청서 예시와 맞춰 **동일한 /participants/{participantId}/... 별칭**도 지원합니다. 동일 인증·소유권 검사·응답 헤더·version/includeConversations 파라미터를 적용합니다. 학생 경로는 기존 /api/evaluations/{id}/report/pdf 및 /report/hwp를 유지합니다.

PDF application/pdf, HWP application/x-hwp. 둘 다 attachment. 저장본 조회는 미생성404 REPORT_NOT_FOUND, POST reports 선행. 단 기존 교사 report.pdf는 version 생략 시 생성/재사용 동작을 유지합니다. 서식 변경이 기존 저장 내용이나 버전을 변경하지 않습니다. V2 과정 평가 결과를 기존 보고서에 자동 병합하지 않으며 저장된 교사 검토·분류만 출력합니다.

## 프런트 확인 요청 답변

- 이벤트: `POST /api/student/evaluations/{id}/events`와 `/api/evaluations/{id}/events`는 별칭입니다. 전체화면 이탈은 FULLSCREEN_EXIT 또는 FULLSCREEN_LEAVE, 개발자 도구는 DEVTOOLS_OPEN. 기존 WINDOW_LEAVE/SHORTCUT_CAPTURE도 유지합니다. 경고 집계·첫 교사 알림에 포함하고 클라이언트 이벤트10종 합산60건/분을 적용합니다.
- 배포: 위 이벤트는 develop890c5a2의 배포 워크플로42376198497 성공으로 운영 반영됐습니다. 교사 /activity의 warnings에 표시합니다. 프런트가 실제 해당 이벤트를 감지해 참가자 토큰으로 전송해야 하며 단순 지원 여부와 실제 브라우저 전달 성공은 별개입니다.
- 답안 기여도: /activity.studentContribution은 실제 작성 비율을 측정할 수 없으므로 null 유지합니다. contribution.direct는 TEXT_SIMILARITY_ESTIMATE(문장 유사도 추정 비중)로 '직접 작성한 실제 비율'이 아닙니다. 새 과정 평가V2의 studentContribution은 25점 행동 기반 역량 평가이며 퍼센트와 구분합니다. 별도 feature/analysis-rubric-v2는 아직 운영 미배포입니다.

## rubric-scores 연결 보완

feature/grading-feedback에서 새 보고서는 실제 rubric-scores를 content.grading에 고정하고 교사 feedback → AI reason 순으로 평가 내용을 표시합니다. 기존 보고서 버전에는 소급하지 않습니다. [GRADING_FEEDBACK.md](GRADING_FEEDBACK.md) 참고.
