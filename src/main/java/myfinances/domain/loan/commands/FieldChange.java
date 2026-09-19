package myfinances.domain.loan.commands;

public record FieldChange<T>(boolean present, T value) {}
