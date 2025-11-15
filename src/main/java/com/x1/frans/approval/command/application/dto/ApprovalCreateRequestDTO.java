package com.x1.frans.approval.command.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.x1.frans.approval.common.ApprovalCategoryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Schema(description = "결재 등록 요청 DTO")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApprovalCreateRequestDTO {

    @Schema(description = "결재 제목")
    @NotNull(message = "결재 제목은 필수입니다.")
    private String title;

    @Schema(description = "결재 내용")
    private String remarks;

    @Schema(description = "요청여부(임시저장)")
    @NotNull(message = "요청여부는 필수입니다.")
    private Boolean isRequest;

    @Schema(description = "결재 문서 유형")
    private ApprovalCategoryType categoryType;

    // todo : 문서 번호만 저장하도록 수정 ( 결재 문서 유형 중복 )
    @Schema(description = "결재에 포함될 문서 정보")
    @NotNull(message = "결재 문서 정보는 필수입니다.")
    private ApprovalDocumentDTO approvalDocuments;

    @Schema(description = "결재선 정보")
    @NotNull(message = "결재선 정보는 필수입니다.")
    private List<ApprovalLineRequestDTO> approvalLines;

    @Schema(description = "첨부파일 목록")
    private List<ApprovalFileDTO> files;

}

