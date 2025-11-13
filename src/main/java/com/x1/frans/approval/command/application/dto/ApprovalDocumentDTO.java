package com.x1.frans.approval.command.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.x1.frans.approval.common.ApprovalCategoryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Schema(description = "결재 문서 정보(주문, 반품, 발주) DTO")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApprovalDocumentDTO {

    @Schema(description = "결재 문서 유형")
    private ApprovalCategoryType categoryType;

    @Schema(description = "결재 문서 ID 목록")
    private List<Long> documentIds;

}