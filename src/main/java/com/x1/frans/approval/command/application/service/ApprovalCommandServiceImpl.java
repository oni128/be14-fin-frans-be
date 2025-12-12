package com.x1.frans.approval.command.application.service;

import com.x1.frans.approval.command.application.dto.*;
import com.x1.frans.approval.command.domain.repository.*;
import com.x1.frans.approval.command.domain.aggregate.*;
import com.x1.frans.approval.common.ApprovalCategoryType;
import com.x1.frans.approval.common.ApprovalLineStatus;
import com.x1.frans.approval.common.ApprovalLineType;
import com.x1.frans.approval.common.ApprovalStatus;
import com.x1.frans.approval.query.repository.ApprovalCategoryQueryMapper;
import com.x1.frans.approval.query.repository.ApprovalQueryMapper;
import com.x1.frans.approval.query.service.ApprovalQueryService;
import com.x1.frans.exception.*;
import com.x1.frans.notification.command.application.service.ApprovalRequestNotificationSender;
import com.x1.frans.notification.command.domain.model.NotificationDomainType;
import com.x1.frans.notification.command.domain.model.NotificationTarget;
import com.x1.frans.user.command.aggregate.UserEntity;
import com.x1.frans.user.command.repository.UserCommandRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;


@Service
@RequiredArgsConstructor
public class ApprovalCommandServiceImpl implements ApprovalCommandService {

    private final UserCommandRepository userCommandRepository;
    private final ApprovalCommandRepository approvalCommandRepository;
    private final ApprovalLineCommandRepository approvalLineCommandRepository;
    private final ApprovalQueryService approvalQueryService;
    private final ApprovalFileCommandRepository approvalFileCommandRepository;
    private final OrderApprovalCommandRepository orderApprovalCommandRepository;
    private final ReturnApprovalCommandRepository returnApprovalCommandRepository;
    private final PurchaseOrderApprovalCommandRepository purchaseOrderApprovalCommandRepository;
    private final ApprovalLineTemplateCommandRepository approvalLineTemplateCommandRepository;
    private final ApprovalLineTemplateDetailCommandRepository approvalLineTemplateDetailCommandRepository;
    private final ApprovalQueryMapper approvalQueryMapper;
    private final ApprovalCategoryQueryMapper approvalCategoryQueryMapper;
    private final ApprovalRequestNotificationSender approvalRequestNotificationSender;

//    private final DepartmentNotificationHelper departmentNotificationHelper;


    /*
    * 결재 등록, 재기안
    *
    * */
    @Transactional
    @Override
    public ApprovalResponseDTO createApproval(ApprovalCreateRequestDTO request, long userId) {

        // 사용자 조회
        UserEntity user = userCommandRepository.findById(userId)
                .orElseThrow(() -> new UserSignatureNotFoundException("기안자 정보를 찾을 수 없습니다."));

        /*
        * 검증
        * : 서명, 제목, 결재문서, 결재선
        * */
        validateRequestForCreate(user, request);


        /*
        * 기본사항 저장
        * : 제목, 내용, 상태, 요청여부, 유저정보, 코드, 차수
        */
        ApprovalEntity approval = createBaseApproval(request, user);


        /*
        * 결재문서 저장
        * : 주문, 반품, 발주
        * */
        saveApprovalDocument(request, approval);


        /*
        * 결재선 기본사항 저장
        * : 타입(결재,협조,참조,수신), 순서, 결재, 유저
        *
        * 순서 필터링 후 [순서, 상태] 저장
        * : orderedLines(결재자, 협조자) → seq 재정의 + status 설정
            disorderLines(참조자, 수신자) → seq null + status null
        * */
        saveApprovalLines(request, approval);


        /*
        * 첨부파일
        * */
        saveFiles(request, approval);

        return new ApprovalResponseDTO(approval.getId(), approval.getCode(), approval.getCreatedAt());
}

    /*
    * 첨부파일
    * */
    private void saveFiles(ApprovalCreateRequestDTO request, ApprovalEntity approval) {

        List<ApprovalFileEntity> files = request.getFiles().stream()
                .map(file -> {
                    ApprovalFileEntity approvalFile = new ApprovalFileEntity();
                    approvalFile.setName(file.getName());
                    approvalFile.setUrl(file.getUrl());
                    approvalFile.setSize(file.getSize());
                    approvalFile.setApproval(approval);

                    return approvalFile;
                }).toList();

        approvalFileCommandRepository.saveAll(files);
    }

    /*
    * 결재선
    * */
    private void saveApprovalLines(ApprovalCreateRequestDTO request, ApprovalEntity approval) {

        // 결재선 기본사항 저장
        // : 타입(결재,협조,참조,수신), 순서(필터링 전), 결재, 유저
        // Todo : N+1 문제 해결하기
        List<ApprovalLineEntity> lines = request.getApprovalLines().stream()
                .map(line -> {
                    UserEntity user = userCommandRepository.findById(line.getUserId())
                            .orElseThrow(() -> new IllegalArgumentException("결재자 정보가 없습니다."));

                    ApprovalLineEntity approvalLine = new ApprovalLineEntity();
                    approvalLine.setApprovalType(ApprovalLineType.valueOf(line.getType()));
                    approvalLine.setSeq(line.getSeq());
                    approvalLine.setApproval(approval);
                    approvalLine.setUser(user);

                    return approvalLine;

                }).toList();


        // 순서가 필요한 라인 필터링 (결재자, 협조자)
        List<ApprovalLineEntity> orderedLines = lines.stream()
                .filter(line -> line.getApprovalType().isOrdered()) // true
//                .sorted(Comparator.comparingInt(ApprovalLineEntity::getSeq))
                // null이 존재해도 에러없이 뒤로 보내는 코드로 수정
                .sorted(Comparator.comparing(ApprovalLineEntity::getSeq, Comparator.nullsLast(Integer::compareTo)))
                .toList();
        // 순서 필요 없는 라인 필터링 (참조자, 수신자)
        List<ApprovalLineEntity> disorderLines = lines.stream()
                .filter(line -> !line.getApprovalType().isOrdered()) // false
                .toList();

        // 필터링된 값을 이전에 만들어놨던 lines에 맞춰 순서(재정의)와, 상태 삽입하기.
        // 순서 필요한 라인 삽입
        for(int i=0; i<orderedLines.size(); i++) {
            ApprovalLineEntity line = orderedLines.get(i);
            line.setSeq(i);
            line.setStatus(line.getApprovalType().getInitialStatus(i));
        }
        // 순서 필요 없는 라인 삽입
        disorderLines.forEach(line -> {
            line.setStatus(null);
            line.setSeq(null);
        });


        approvalLineCommandRepository.saveAll(lines);


        // 알림 !
        if (request.getIsRequest()) {
            NotificationTarget target = new NotificationTarget(
                    NotificationDomainType.APPROVAL,
                    approval.getId(),
                    "/approval/" + approval.getId()
            );

            // 1. 결재자/협조자에게 알림 전송
            for (ApprovalLineEntity line : lines) {
                if (line.getApprovalType() == ApprovalLineType.APPROVER
                        || line.getApprovalType() == ApprovalLineType.COOPERATOR) {
                    approvalRequestNotificationSender.notifyApprovalRequested(
                            approval.getId(),
                            line.getUser().getId(),
                            target
                    );
                }
            }
        }
    }

    /*
     * 결재문서 저장
     * : 주문, 반품, 발주
     * */
    private void saveApprovalDocument(ApprovalCreateRequestDTO request, ApprovalEntity approval) {
        // 문서 유형에 따라 구분하여 저장.
        ApprovalCategoryType type = request.getCategoryType();
        List<Long> docIds = request.getApprovalDocuments().getDocumentIds();

        switch (type) {
            case ORDER -> {
                List<OrderApprovalEntity> orderDocs = docIds.stream()
                        .map(docId -> new OrderApprovalEntity(approval,docId))
                        .toList();
                orderApprovalCommandRepository.saveAll(orderDocs);
            }

            case RETURN -> {
                List<ReturnApprovalEntity> returnDocs = docIds.stream()
                        .map(docId -> new ReturnApprovalEntity(approval, docId))
                        .toList();

                returnApprovalCommandRepository.saveAll(returnDocs);
            }

            case PURCHASE_ORDER -> {
                List<PurchaseOrderApprovalEntity> purchaseOrderDocs = docIds.stream()
                        .map(docId -> new PurchaseOrderApprovalEntity(approval, docId))
                        .toList();

                purchaseOrderApprovalCommandRepository.saveAll(purchaseOrderDocs);
            }

            default -> throw new IllegalArgumentException("알 수 없는 문서 유형입니다.");
        }
    }

    /*
     * 기본사항 DB에 저장
     * : 제목, 내용, 상태, 요청여부, 유저정보, 코드, 차수
     */
    private ApprovalEntity createBaseApproval(ApprovalCreateRequestDTO request, UserEntity user) {
        // 제목, 내용, 상태, 요청여부, 유저정보, 코드, 차수

        // 결재 코드 생성
        String newCode = generateApprovalCode();
        // 결재 차수
        int degree = approvalCommandRepository.findMaxDegreeByCode(newCode) + 1;

        ApprovalEntity approval = new ApprovalEntity();
        approval.setTitle(request.getTitle());
        approval.setRemarks(request.getRemarks());
        approval.setStatus(request.getIsRequest() ? ApprovalStatus.IN_PROGRESS: ApprovalStatus.DRAFT);
        approval.setUser(user);
        approval.setCode(newCode);
        approval.setDegree(degree);
        approval.setIsRequested(request.getIsRequest());

        return approvalCommandRepository.save(approval);

    }

    /* 결재등록 검증
    *  : 서명, 제목, 결재문서, 결재선
    * */
    private void validateRequestForCreate(UserEntity user, ApprovalCreateRequestDTO request) {
        validateSignature(user);
        validateTitle(request);
        validateApprovalDocument(request);
        validateApprovalLines(request);
    }

    // 결재선 검증
    private void validateApprovalLines(ApprovalCreateRequestDTO request) {
        List<ApprovalLineRequestDTO> Lines = request.getApprovalLines();

        if(Lines == null || Lines.isEmpty()) {
            throw new IllegalArgumentException("결재선 최소 1명 이상 등록되어야 합니다.");
        }
    }

    // 결재문서 검증
    private void validateApprovalDocument(ApprovalCreateRequestDTO request) {
        ApprovalDocumentDTO doc = request.getApprovalDocuments();

        if(doc == null || doc.getDocumentIds() == null || doc.getDocumentIds().isEmpty()) {
            throw new IllegalArgumentException("결재 문서는 최소 1개 이상 등록해야 합니다.");
        }

        if(request.getCategoryType() == null) {
            throw new IllegalArgumentException("결재 문서의 유형이 존재하지 않습니다.");
        }
    }

    // 제목 검증
    private void validateTitle(ApprovalCreateRequestDTO request) {
        String title = request.getTitle();

        if(title == null || title.isBlank()) {
            throw new IllegalArgumentException("제목은 필수 입력 항목입니다.");
        }
    }

    // 서명 검증
    private void validateSignature(UserEntity user) {
        String sign = user.getSignUrl();
        if(sign == null || sign.isBlank()) {
            throw new IllegalStateException("서명이 등록되지 않은 사용자는 결재 등록이 불가능합니다.");
        }
    }

    private String generateApprovalCode() {
        String prefix = "APP";
        String yearMonth = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String codePrefix = prefix + yearMonth;

        String latestCode = approvalQueryService.findLatestApprovalCode(codePrefix);

        return generateSequentialCode(codePrefix, latestCode);
    }


    private String generateSequentialCode(String codePrefix, String latestCode) {
        int nextSeq = 1;

        if (latestCode != null && latestCode.startsWith(codePrefix)) {
            String seqStr = latestCode.substring(codePrefix.length());
            try {
                nextSeq = Integer.parseInt(seqStr) + 1;
            } catch (NumberFormatException e) {
                // 예외 발생 시 기본값 유지
            }
        }

        return String.format("%s%04d", codePrefix, nextSeq);
    }

    @Override
    public ApprovalResponseDTO createApprovalDrafts(ApprovalDraftCreateRequestDTO request, long userId) {

        // 사용자 조회
        UserEntity user = userCommandRepository.findById(userId)
                .orElseThrow(() -> new UserSignatureNotFoundException("기안자 정보를 찾을 수 없습니다."));

        // 결재 코드 생성
        String newCode = generateApprovalCode();

        int degree = 1;
        degree = approvalCommandRepository.findMaxDegreeByCode(newCode) + 1;


        // 결재 엔티티 생성
        ApprovalEntity approval = new ApprovalEntity();
        approval.setTitle(request.getTitle());
        approval.setRemarks(request.getRemarks());
        approval.setStatus(ApprovalStatus.DRAFT);
        approval.setIsRequested(false);
        approval.setUser(user); // 연관관계 설정
        approval.setCode(newCode);
        approval.setDegree(degree);

        // 저장
        approvalCommandRepository.save(approval);


        // 결재 문서
//        ApprovalDocumentDTO doc = request.getApprovalDocuments();
        ApprovalCategoryType docCategory = request.getCategoryType();
        List<Long> docIds = request.getApprovalDocumentId();

        switch (docCategory) {
            case ORDER -> {
                List<OrderApprovalEntity> orderDocs = docIds.stream()
                        .map(docId -> new OrderApprovalEntity(approval, docId))
                        .toList();
                orderApprovalCommandRepository.saveAll(orderDocs);
            }

            case RETURN -> {
                List<ReturnApprovalEntity> returnDocs = docIds.stream()
                        .map(docId -> new ReturnApprovalEntity(approval, docId))
                        .toList();
                returnApprovalCommandRepository.saveAll(returnDocs);
            }

            case PURCHASE_ORDER -> {
                List<PurchaseOrderApprovalEntity> purchaseOrderDocs = docIds.stream()
                        .map(docId -> new PurchaseOrderApprovalEntity(approval, docId))
                        .toList();
                purchaseOrderApprovalCommandRepository.saveAll(purchaseOrderDocs);
            }

            default -> throw new IllegalArgumentException("알 수 없는 문서 유형입니다.");
        }

        // 결재선 처리
        List<ApprovalLineEntity> approvalLines = request.getApprovalLines().stream()
                .map(line -> {
                    UserEntity approver = userCommandRepository.findById(line.getUserId())
                            .orElseThrow(() -> new UserNotFoundException("결재자 정보를 찾을 수 없습니다."));

                    ApprovalLineEntity approvalLine = new ApprovalLineEntity();
                    approvalLine.setApproval(approval);
                    approvalLine.setUser(approver);
                    approvalLine.setSeq(line.getSeq());
                    approvalLine.setApprovalType(ApprovalLineType.valueOf(line.getType()));
                    return approvalLine;
                }).toList();


        // 순서가 필요한 라인만 필터링 (결재자, 협조자)
        List<ApprovalLineEntity> orderedLines = approvalLines.stream()
                .filter(line -> line.getApprovalType().isOrdered())
                .sorted(Comparator.comparingInt(ApprovalLineEntity::getSeq))
                .collect(toList());

        // 순서 필요 없는 나머지 (참조자, 수신자)
        List<ApprovalLineEntity> referenceLines = approvalLines.stream()
                .filter(line -> !line.getApprovalType().isOrdered())
                .collect(toList());

        // 상태 설정 enum 메서드에 위임
        for (int i = 0; i < orderedLines.size(); i++) {
            ApprovalLineEntity line = orderedLines.get(i);
            line.setStatus(line.getApprovalType().getInitialStatus(i));
        }


        // 결재선 저장
        List<ApprovalLineEntity> allLines = new ArrayList<>();
        allLines.addAll(orderedLines);
        allLines.addAll(referenceLines);

        // 참조자/수신자는 상태 없이 저장
        referenceLines.forEach(line -> line.setStatus(null));

        approvalLineCommandRepository.saveAll(allLines);

        // 첨부파일
        List<ApprovalFileEntity> fileEntities = request.getFiles()== null ? List.of() :
                request.getFiles().stream()
                        .map(file -> {
                            ApprovalFileEntity approvalFile = new ApprovalFileEntity();
                            approvalFile.setApproval(approval);
                            approvalFile.setName(file.getName());
                            approvalFile.setUrl(file.getUrl());
                            return approvalFile;
                        }).toList();

        approvalFileCommandRepository.saveAll(fileEntities);
        return new ApprovalResponseDTO(approval.getId(), approval.getCode(), approval.getCreatedAt());
    }

    @Transactional
    @Override
    public Optional<ApprovalResponseDTO> approverApprove(ApprovalApproveRequestDTO request, long approvalId, long userId) {

        // 결재 본문 조회
        ApprovalEntity approval = approvalCommandRepository.findById(approvalId)
                .orElseThrow(() -> new ApprovalNotFoundException("결재를 찾을 수 없습니다."));

        // 사용자 조회
        UserEntity user = userCommandRepository.findById(userId)
                .orElseThrow(() -> new UserSignatureNotFoundException("결재자 정보를 찾을 수 없습니다."));

        // 현재 사용자의 결재선 찾기
        ApprovalLineEntity currentLine = approvalLineCommandRepository.findByApprovalIdAndUserId(approval.getId(), userId)
                .orElseThrow(() -> new ApprovalLineNotFoundException("해당 사용자의 결재선이 없습니다."));

        if (user.getSignUrl() == null || user.getSignUrl().isBlank()) {
            throw new IllegalStateException("서명이 등록되지 않은 사용자는 결재 등록이 불가능합니다.");
        }

        if (!currentLine.getStatus().equals(ApprovalLineStatus.WAITING)) {
            throw new ApprovalActionFailedException("결재선이 대기 상태가 아닙니다.");
        }

        // 결재 or 협조인 경우 → 상태를 승인으로 변경
        if ("결재".equals(request.getApprovalType()) || "협조".equals(request.getApprovalType())) {
            currentLine.setStatus(ApprovalLineStatus.APPROVED);
            currentLine.setOpinion(request.getOpinion());
            currentLine.setProcessedAt(LocalDateTime.now());
            approvalLineCommandRepository.save(currentLine);
        }

        // 다음 결재선 대상 찾기
        int nextSeq = currentLine.getSeq() + 1;
        Optional<ApprovalLineEntity> nextLineOpt = approvalLineCommandRepository.findByApprovalIdAndSeq(approval.getId(), nextSeq);

        // 다음 결재선이 결재자 or 협조자인 경우에만 상태를 WAITING 으로 변경
        boolean nextIsApprovalOrCooperator = nextLineOpt
                .filter(nextLine -> List.of(ApprovalLineType.APPROVER, ApprovalLineType.COOPERATOR).contains(nextLine.getApprovalType()))
                .isPresent();

        if (nextIsApprovalOrCooperator) {
            ApprovalLineEntity nextLine = nextLineOpt.get();
            if (nextLine.getStatus() == ApprovalLineStatus.EXPECTED) {
                nextLine.setStatus(ApprovalLineStatus.WAITING);
                approvalLineCommandRepository.save(nextLine);
            }
        } else {
            // 다음 결재선이 없거나, 참조/수신만 남은 경우 → 결재 종료 조건 검사
            boolean hasRemainingApprovalOrCooperator =
                    approvalLineCommandRepository.existsByApprovalIdAndApprovalTypeInAndStatusIn(
                            approvalId,
                            List.of(ApprovalLineType.APPROVER, ApprovalLineType.COOPERATOR),
                            List.of(ApprovalLineStatus.WAITING, ApprovalLineStatus.EXPECTED)
                    );

            if (!hasRemainingApprovalOrCooperator) {
                approval.setStatus(ApprovalStatus.APPROVED);
                approval.setProcessedAt(LocalDateTime.now());
                approvalCommandRepository.save(approval);

                // 카테고리별 상태 업데이트
                String category = approvalQueryMapper.findCategoryByApprovalId(approval.getId());
                switch (category) {
                    case "ORDER" -> approvalCategoryQueryMapper.updateOrderStatusByApprovalId(approvalId, "APPROVED");
                    case "RETURN" -> approvalCategoryQueryMapper.updateReturnStatusByApprovalId(approvalId, "APPROVED");
                    case "PURCHASE_ORDER" -> approvalCategoryQueryMapper.updatePurchaseOrderStatusByApprovalId(approvalId, "승인 완료");
                    default -> throw new IllegalStateException("알 수 없는 결재 카테고리입니다: " + category);
                }
            }
        }

        return Optional.of(new ApprovalResponseDTO(approval.getId(), approval.getCode(), approval.getCreatedAt()));
    }

    @Transactional
    @Override
    public Optional<ApprovalResponseDTO> approverReject(ApprovalRejectRequestDTO request, long approvalId, long userId) {

        // 결재 본문 조회
        ApprovalEntity approval = approvalCommandRepository.findById(approvalId)
                .orElseThrow(() -> new ApprovalNotFoundException("결재를 찾을 수 없습니다."));

        // 현재 사용자의 결재선 찾기
        ApprovalLineEntity line = approvalLineCommandRepository.findByApprovalIdAndUserId(approval.getId(), userId)
                .orElseThrow(() -> new ApprovalLineNotFoundException("해당 사용자의 결재선이 없습니다."));

        // 사용자 조회
        UserEntity user = userCommandRepository.findById(userId)
                .orElseThrow(() -> new UserSignatureNotFoundException("결재자 정보를 찾을 수 없습니다."));


        if (user.getSignUrl() == null || user.getSignUrl().isBlank()) {
            throw new IllegalStateException("서명이 등록되지 않은 사용자는 결재 등록이 불가능합니다.");
        }

        if (!line.getStatus().equals(ApprovalLineStatus.WAITING)) {
            throw new ApprovalActionFailedException("결재선이 대기 상태가 아닙니다.");
        }


        // 결재 or 협조인 경우 → 상태를 반려로 변경
        if ("결재".equals(request.getApprovalType()) || "협조".equals(request.getApprovalType())) {
            line.setStatus(ApprovalLineStatus.REJECTED);
            line.setOpinion(request.getOpinion());
            line.setProcessedAt(LocalDateTime.now());

            approvalLineCommandRepository.save(line);
        }

            // 결재 상태 반려 처리
            approval.setStatus(ApprovalStatus.REJECTED);
            approval.setProcessedAt(LocalDateTime.now());
            approvalCommandRepository.save(approval);

        NotificationTarget target = new NotificationTarget(
                NotificationDomainType.APPROVAL,
                approval.getId(),
                "/approval/" + approval.getId()
        );

        approvalRequestNotificationSender.notifyApprovalRejected(approval.getId(), approval.getUser().getId(), target);

        return Optional.of(new ApprovalResponseDTO(approval.getId(), approval.getCode(), approval.getCreatedAt()));
    }

    @Transactional
    @Override
    public Optional<ApprovalResponseDTO> approvalLineTemplates(ApprovalLineTemplateCreateRequestDTO request, long userId) {

        UserEntity owner = userCommandRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("사용자를 찾을 수 없습니다."));

        BigDecimal maxSeq = approvalLineTemplateCommandRepository.findMaxSeq();
        BigDecimal nextSeq = maxSeq.add(new BigDecimal("1.0000"));

        ApprovalLineTemplateEntity template = new ApprovalLineTemplateEntity();
        template.setName(request.getName());
        template.setDescription(request.getDescription());
        template.setSeq(nextSeq);
        template.setUser(owner);


        List<ApprovalLineTemplateDetailEntity> details = new ArrayList<>();

        for (ApprovalTemplateLineDTO dto : request.getLines()) {
            UserEntity approver = userCommandRepository.findById(dto.getUserId())
                    .orElseThrow(() -> new RuntimeException("결재선 사용자가 아닙니다."));

            ApprovalLineTemplateDetailEntity detail = new ApprovalLineTemplateDetailEntity();
            detail.setUser(approver);
            detail.setSeq(dto.getSeq());
            detail.setType(dto.getType());
            detail.setTemplate(template);

            details.add(detail);
        }

        template.setDetails(details);
        approvalLineTemplateCommandRepository.save(template);

        return Optional.of(new ApprovalResponseDTO(template.getId(), template.getName()));
    }

    @Transactional
    @Override
    public Optional<ApprovalResponseDTO> approvalLineTemplatesModify(ApprovalLineTemplateModifyRequestDTO request, long userId, Long templateId) {
        if (templateId == null) {
            throw new IllegalArgumentException("템플릿 ID가 없습니다.");
        }

        ApprovalLineTemplateEntity template = approvalLineTemplateCommandRepository.findById(templateId)
                .orElseThrow(() -> new ApprovalLineTemplateNotFoundException("템플릿을 찾을 수 없습니다."));

        // 본인 소유 템플릿인지 확인
        if (template.getUser() == null || !template.getUser().getId().equals(userId)) {
            throw new UnauthorizedTemplateAccessException("수정 권한이 없습니다.");
        }

        //  템플릿 정보 업데이트
        template.setName(request.getName());
        template.setDescription(request.getDescription());
        approvalLineTemplateCommandRepository.save(template);

        // 기존 상세 결재선 삭제
        approvalLineTemplateDetailCommandRepository.deleteByTemplateId(templateId);

        // 새 상세 결재선 등록
        List<ApprovalLineTemplateDetailEntity> newDetails = request.getLines().stream()
                .map(line -> {

                    ApprovalLineTemplateDetailEntity detail = new ApprovalLineTemplateDetailEntity();

                    UserEntity approver = userCommandRepository.findById(line.getUserId())
                            .orElseThrow(() -> new RuntimeException("결재선 사용자를 찾을 수 없습니다."));
                    detail.setUser(approver);
                    detail.setTemplate(template);
                    detail.setType(line.getType());
                    detail.setSeq(line.getSeq());
                    return detail;
                })
                .collect(Collectors.toList());
        approvalLineTemplateDetailCommandRepository.saveAll(newDetails);

        return Optional.of(new ApprovalResponseDTO(template.getId(), template.getName()));
    }

    @Transactional
    @Override
    public Optional<ApprovalResponseDTO> deleteApprovalLineTemplates(long userId, Long templateId) {
        if (templateId == null) {
            throw new IllegalArgumentException("템플릿 ID가 없습니다.");
        }

        ApprovalLineTemplateEntity template = approvalLineTemplateCommandRepository.findById(templateId)
                .orElseThrow(() -> new ApprovalLineTemplateNotFoundException("템플릿을 찾을 수 없습니다."));

        // 본인 소유 템플릿인지 확인
        if (template.getUser() == null || !template.getUser().getId().equals(userId)) {
            throw new UnauthorizedTemplateAccessException("수정 권한이 없습니다.");
        }
        approvalLineTemplateCommandRepository.delete(template);

        return Optional.of(new ApprovalResponseDTO(template.getId(), template.getName()));
    }

    @Transactional
    @Override
    public ApprovalResponseDTO modifyApproval(ApprovalCreateRequestDTO request, long userId, long approvalId) {

        // 결재 문서 조회
        ApprovalEntity approval = approvalCommandRepository.findById(approvalId)
                .orElseThrow(() -> new ApprovalNotFoundException("결재 문서를 찾을 수 없습니다."));

        // 기안자 조회
        if (!approval.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException("결재 문서를 수정할 권한이 없습니다.");
        }



        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("제목은 필수 입력값입니다.");
        }
        if (request.getApprovalLines().isEmpty()) {
            throw new IllegalArgumentException("결재선은 최소 1명 이상이어야 합니다.");
        }
        if (approval.getDegree() == null) {
            throw new IllegalStateException("approval degree 값이 누락되었습니다.");
        }
        if (request.getApprovalDocuments() == null || request.getApprovalDocuments().getDocumentIds().isEmpty()) {
            throw new IllegalArgumentException("문서 정보가 비어 있습니다.");
        }


        approvalFileCommandRepository.deleteByApprovalId(approvalId);
        approvalLineCommandRepository.deleteByApprovalId(approvalId);
        returnApprovalCommandRepository.deleteByApprovalId(approvalId);
        orderApprovalCommandRepository.deleteByApprovalId(approvalId);


        approval.setTitle(request.getTitle());
        approval.setRemarks(request.getRemarks());

        if (request.getIsRequest()) {
            approval.setIsRequested(true); // 결재 진행중
            approval.setStatus(ApprovalStatus.IN_PROGRESS);
            approval.setCreatedAt(LocalDateTime.now());

            // degree 증가 처리
            if (approval.getDegree() == null) {
                approval.setDegree(1); // 최초 등록
            } else {
                approval.setDegree(approval.getDegree() + 1); // 재기안
            }

        } else {
            approval.setIsRequested(false); // 임시저장
            approval.setStatus(ApprovalStatus.DRAFT);
            approval.setCreatedAt(LocalDateTime.now());

            // 임시저장일 경우 degree를 0으로 설정 -기안 전 상태
            if (approval.getDegree() == null) {
                approval.setDegree(0);
            }
        }
        purchaseOrderApprovalCommandRepository.deleteByApprovalId(approvalId);




        // 결재 문서
        ApprovalDocumentDTO doc = request.getApprovalDocuments();

        switch (doc.getCategoryType()) {
            case ORDER -> {
                List<OrderApprovalEntity> orderDocs = doc.getDocumentIds().stream()
                        .map(docId -> new OrderApprovalEntity(approval, docId))
                        .toList();
                orderApprovalCommandRepository.saveAll(orderDocs);
            }

            case RETURN -> {
                List<ReturnApprovalEntity> returnDocs = doc.getDocumentIds().stream()
                        .map(docId -> new ReturnApprovalEntity(approval, docId))
                        .toList();
                returnApprovalCommandRepository.saveAll(returnDocs);
            }

            case PURCHASE_ORDER -> {
                List<PurchaseOrderApprovalEntity> purchaseOrderDocs = doc.getDocumentIds().stream()
                        .map(docId -> new PurchaseOrderApprovalEntity(approval, docId))
                        .toList();
                purchaseOrderApprovalCommandRepository.saveAll(purchaseOrderDocs);
            }

            default -> throw new IllegalArgumentException("알 수 없는 문서 유형입니다.");
        }

        // 결재선 처리
        List<ApprovalLineEntity> approvalLines = request.getApprovalLines().stream()
                .map(line -> {
                    UserEntity approver = userCommandRepository.findById(line.getUserId())
                            .orElseThrow(() -> new UserNotFoundException("결재자 정보를 찾을 수 없습니다."));

                    ApprovalLineEntity approvalLine = new ApprovalLineEntity();
                    approvalLine.setApproval(approval);
                    approvalLine.setUser(approver);
                    approvalLine.setSeq(line.getSeq());
                    approvalLine.setApprovalType(ApprovalLineType.valueOf(line.getType()));
//                    if (approval.getDegree() != null) { // ApprovalLineEntity에서 degree를 제거하면서 오류발생하여 주석처리함
//                        approvalLine.setApprovalDegree(approval.getDegree().longValue());
//                    }
                    return approvalLine;
                }).toList();


        // 순서가 필요한 라인만 필터링 (결재자, 협조자)
        List<ApprovalLineEntity> orderedLines = approvalLines.stream()
                .filter(line -> line.getApprovalType().isOrdered())
                .sorted(Comparator.comparingInt(ApprovalLineEntity::getSeq))
                .collect(toList());

        // 순서 필요 없는 나머지 (참조자, 수신자)
        List<ApprovalLineEntity> referenceLines = approvalLines.stream()
                .filter(line -> !line.getApprovalType().isOrdered())
                .collect(toList());

        // 상태 설정 enum 메서드에 위임
        for (int i = 0; i < orderedLines.size(); i++) {
            ApprovalLineEntity line = orderedLines.get(i);
            line.setStatus(line.getApprovalType().getInitialStatus(i));
        }


        // 결재선 저장
        List<ApprovalLineEntity> allLines = new ArrayList<>();
        allLines.addAll(orderedLines);
        allLines.addAll(referenceLines);

        // 참조자/수신자는 상태 없이 저장
        referenceLines.forEach(line -> line.setStatus(null));

        approvalLineCommandRepository.saveAll(allLines);


        // 첨부파일
        List<ApprovalFileEntity> fileEntities = request.getFiles()== null ? List.of() :
                request.getFiles().stream()
                        .map(file -> {
                            ApprovalFileEntity approvalFile = new ApprovalFileEntity();
                            approvalFile.setApproval(approval);
                            approvalFile.setName(file.getName());
                            approvalFile.setUrl(file.getUrl());
                            return approvalFile;
                        }).toList();

        approvalFileCommandRepository.saveAll(fileEntities);


        return new ApprovalResponseDTO(approval.getId(), approval.getTitle());

    }

    @Transactional
    @Override
    public ApprovalResponseDTO updateRequestState(ApprovalStatusUpdateDTO request, long userId, long approvalId) {

        ApprovalEntity approval = approvalCommandRepository.findById(approvalId)
                .orElseThrow(() -> new ApprovalNotFoundException("결재문서가 존재하지 않습니다."));


        if (!approval.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException("결재 수정 권한 없습니다.");
        }

        approval.setIsRequested(request.getIsRequested());

        // 요청이 해제 (isRequested == false) → 상태를 DRAFT로 변경
        if (Boolean.FALSE.equals(request.getIsRequested())) {
            approval.setStatus(ApprovalStatus.DRAFT);
        }

        approvalCommandRepository.save(approval);


        return new ApprovalResponseDTO(approval.getId(), approval.getTitle());

    }

    @Transactional
    @Override
    public ApprovalResponseDTO requestApproval(long userId, long approvalId) {

        ApprovalEntity approval = approvalCommandRepository.findById(approvalId)
                .orElseThrow(() -> new ApprovalNotFoundException("결재문서가 존재하지 않습니다."));

        if (!approval.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException("결재 수정 권한이 없습니다.");
        }

        if (approval.getIsRequested()) {
            throw new IllegalStateException("이미 결재 요청된 문서입니다.");
        }

        approval.setIsRequested(true);
        approval.setStatus(ApprovalStatus.IN_PROGRESS);

        approvalCommandRepository.save(approval);

        return new ApprovalResponseDTO(approval.getId(), approval.getCode(), approval.getCreatedAt());
    }

    @Transactional
    @Override
    public void approvalLineTemplatesSeqModify(long userId, Long templateId, int targetIndex) {
        // 현재 템플릿
        ApprovalLineTemplateEntity template = approvalLineTemplateCommandRepository.findById(templateId)
                .orElseThrow(() -> new ApprovalLineTemplateNotFoundException("템플릿을 찾을 수 없습니다."));

        // 전체 템플릿 리스트 (내림차순 정렬: seq 큰 게 앞에)
        List<ApprovalLineTemplateEntity> templates =
                approvalLineTemplateCommandRepository.findByUserIdOrderBySeqDesc(userId);

        // 변경될 템플릿은 리스트에서 제거
        templates.removeIf(t -> Objects.equals(t.getId(), templateId));

        final BigDecimal STEP = new BigDecimal("1.0000");
        BigDecimal newSeq;

        if (templates.isEmpty()) {
            // 아무 것도 없을 때
            newSeq = STEP;
        } else if (targetIndex <= 0) {
            // 처음 삽입
            newSeq = templates.get(0).getSeq().add(STEP);
        } else if (targetIndex >= templates.size()) {
            // 마지막 삽입
            newSeq = templates.get(templates.size() - 1).getSeq().subtract(STEP);
        } else {
            // 중간 삽입
            BigDecimal prevSeq = templates.get(targetIndex - 1).getSeq();
            BigDecimal nextSeq = templates.get(targetIndex).getSeq();
            newSeq = prevSeq.add(nextSeq).divide(new BigDecimal("2.0000"), 4, RoundingMode.HALF_UP);
        }
        template.setSeq(newSeq);
    }

    @Transactional
    @Override
    public ApprovalResponseDTO deleteApprovalDrafts(Long approvalId, long userId) {
        ApprovalEntity draft = approvalCommandRepository.findById(approvalId)
                .orElseThrow(() -> new ApprovalNotFoundException("해당 임시저장 문서를 찾을 수 없습니다."));

        if (!draft.getUser().getId().equals(userId)) {
            throw new UnauthorizedAccessException("삭제 권한이 없습니다.");
        }

        if (!draft.getStatus().equals(ApprovalStatus.DRAFT)) {
            throw new InvalidApprovalStateException("임시저장 상태의 문서만 삭제할 수 있습니다.");
        }

        Integer degree = draft.getDegree();

        // 어떤 문서에 연결되었는지 확인 후 삭제
            if (orderApprovalCommandRepository.existsByApproval_IdAndApproval_Degree(approvalId, degree)) {
                orderApprovalCommandRepository.deleteByApproval_IdAndApproval_Degree(approvalId, degree);
            } else if (returnApprovalCommandRepository.existsByApproval_IdAndApproval_Degree(approvalId, degree)) {
                returnApprovalCommandRepository.deleteByApproval_IdAndApproval_Degree(approvalId, degree);
            } else if (purchaseOrderApprovalCommandRepository.existsByApproval_IdAndApproval_Degree(approvalId, degree)) {
                purchaseOrderApprovalCommandRepository.deleteByApproval_IdAndApproval_Degree(approvalId, degree);
            }

        approvalFileCommandRepository.deleteByApprovalIdAndDegree(approvalId, degree);
        approvalLineCommandRepository.deleteByApprovalIdAndDegree(approvalId, draft.getDegree());
        approvalCommandRepository.delete(draft);

        return new ApprovalResponseDTO(draft.getId(), "삭제 완료된 임시 문서입니다.");
    }
}
