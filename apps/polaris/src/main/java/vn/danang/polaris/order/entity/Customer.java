// domain/Customer.java
package vn.danang.polaris.order.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "customers")
@Getter @Setter
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    // Personal / Identity details
    private String fullName;
    private String firstName;
    private String lastName;
    private String email;
    private String secondaryEmail;
    private String phone;
    private String mobilePhone;
    private String dateOfBirth;
    private String gender;
    private String avatarUrl;

    // Identity-provider subject (JWT `sub`) of the shopper account linked to this customer
    private String authSubject;

    // Organization / Business details
    private String company;
    private String jobTitle;
    private String department;
    private String taxId;

    // Billing address details
    private String billingAddressLine1;
    private String billingAddressLine2;
    private String billingCity;
    private String billingState;
    private String billingPostalCode;
    private String billingCountry;

    // Shipping address details
    private String shippingAddressLine1;
    private String shippingAddressLine2;
    private String shippingCity;
    private String shippingState;
    private String shippingPostalCode;
    private String shippingCountry;

    // Account status & CRM notes
    private String customerTier;
    private String status;
    private String notes;

    // Audit timestamps
    private Instant createdAt;
    private Instant updatedAt;
}