package com.maximov.grusha.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.sql.Timestamp;

@Entity
@Table(name = "bookings")
@Data
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chatId;

    private String name;
    private String phone;

    // Новое поле
    private Integer guests;

    private LocalDateTime bookingDateTime;

    private String status = "ACTIVE";

    private Timestamp createdAt = new Timestamp(System.currentTimeMillis());
}
