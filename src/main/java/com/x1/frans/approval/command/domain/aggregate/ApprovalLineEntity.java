package com.x1.frans.approval.command.domain.aggregate;

import com.x1.frans.approval.common.ApprovalLineStatus;
import com.x1.frans.approval.common.ApprovalLineType;
import com.x1.frans.user.command.aggregate.UserEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "approval_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_type", nullable = false)
    private ApprovalLineType approvalType;  // 결재, 협조, 참조, 수신

    @Column(name = "is_checked", nullable = false)
    private Boolean isChecked;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Enumerated(EnumType.STRING)
    private ApprovalLineStatus status;  // 승인, 반려, 대기, 예정

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    private String opinion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "approval_id", referencedColumnName = "id"),
            @JoinColumn(name = "approval_degree", referencedColumnName = "degree")
    })
    private ApprovalEntity approval;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @PrePersist
    protected void onCreate() {
        this.isChecked = false;
    }
}

