package com.x1.frans.approval.command.domain.aggregate;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "approval_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String url;

    private Integer size;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "approval_id", referencedColumnName = "id"),
            @JoinColumn(name = "approval_degree", referencedColumnName = "degree")
    })
    private ApprovalEntity approval;
}
