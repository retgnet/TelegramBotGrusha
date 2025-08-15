package com.maximov.grusha.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

@Entity(name = "usersDataTable")
public class User {

    @Id @Getter @Setter
    private Long chatId;
    @Getter @Setter
    private String firstName;
    @Getter @Setter
    private String lastName;
    @Getter @Setter
    private String userName;
    @Getter @Setter
    private Timestamp registeredAt;
    @Getter @Setter
    private String phoneNumber;
    @Getter @Setter
    private boolean isBooking;
    @Getter @Setter
    private Timestamp bookingTime;

    private boolean Booking;

    public boolean getBooking() {
        return Booking;
    }

    public void setBooking(boolean booking) {
        Booking = booking;
    }
}
