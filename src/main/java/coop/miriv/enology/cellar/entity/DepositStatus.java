package coop.miriv.enology.cellar.entity;

/** RF-DEP-03: operative states of the physical vessel, independent from its content. */
public enum DepositStatus {
    AVAILABLE,
    OCCUPIED,
    PENDING_CLEANING,
    CLEANING,
    MAINTENANCE
}
