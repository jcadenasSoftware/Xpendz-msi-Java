package com.myfinaces.config;

import com.myfinaces.db.AccountRepository;

import java.util.List;
import java.util.Map;

/**
 * Centralised visual identity for account types.
 * <p>
 * Every UI module (Dashboard, Transfers, Loans, Budget, Edit-Account, etc.)
 * should reference this class instead of maintaining its own copy of colours,
 * icons and labels.
 */
public final class AccountStyles {

    private AccountStyles() {
    }

    // ══════════════════════════════════════════════════════════════════
    //  S T Y L E   R E C O R D
    // ══════════════════════════════════════════════════════════════════

    /**
     * Immutable visual descriptor for a single account type.
     *
     * @param key       normalised DB key (e.g. "BANK")
     * @param label     human-readable label (e.g. "Banco")
     * @param icon      Ikonli icon literal (e.g. "fas-home")
     * @param color     primary hex colour (e.g. "#2563EB")
     * @param softColor soft/background hex colour (e.g. "#DBEAFE")
     */
    public record AccountTypeStyle(
        String key,
        String label,
        String icon,
        String color,
        String softColor
    ) {
    }

    // ══════════════════════════════════════════════════════════════════
    //  C A N O N I C A L   D E F I N I T I O N S
    // ══════════════════════════════════════════════════════════════════

    public static final AccountTypeStyle BANK = new AccountTypeStyle(
        "BANK", "Banco", "fas-home", "#2563EB", "#DBEAFE"
    );

    public static final AccountTypeStyle CREDIT = new AccountTypeStyle(
        "CREDIT", "Crédito", "fas-hand-holding-usd", "#7C3AED", "#EDE9FE"
    );

    public static final AccountTypeStyle CASH = new AccountTypeStyle(
        "CASH", "Efectivo", "fas-coins", "#059669", "#D1FAE5"
    );

    public static final AccountTypeStyle SAVINGS = new AccountTypeStyle(
        "SAVINGS", "Ahorro", "fas-piggy-bank", "#D97706", "#FEF3C7"
    );

    public static final AccountTypeStyle VIRTUAL_WALLET = new AccountTypeStyle(
        "VIRTUAL_WALLET", "Billetera virtual", "fas-wallet", "#0891B2", "#CFFAFE"
    );

    public static final AccountTypeStyle DIGITAL_ACCOUNT = new AccountTypeStyle(
        "DIGITAL_ACCOUNT", "Cuenta digital", "fas-calculator", "#E11D48", "#FFE4E6"
    );

    /** Default style used when the type key is unrecognised. */
    public static final AccountTypeStyle DEFAULT = new AccountTypeStyle(
        "BANK", "Cuenta", "fas-university", "#64748B", "#F1F5F9"
    );

    /** Ordered list of all supported types (useful for type-selector grids). */
    public static final List<AccountTypeStyle> ALL_TYPES = List.of(
        BANK, CREDIT, CASH, SAVINGS, VIRTUAL_WALLET, DIGITAL_ACCOUNT
    );

    /** Palette of personalisation colours offered in the "new account" drawer. */
    public static final List<String> ACCOUNT_COLORS = List.of(
        "#2563EB", "#7C3AED", "#059669", "#D97706",
        "#0891B2", "#E11D48", "#4F46E5", "#0D9488",
        "#CA8A04", "#DC2626", "#6366F1", "#14B8A6",
        "#EA580C", "#EAB308", "#F43F5E", "#475569"
    );

    // Fast lookup by key
    private static final Map<String, AccountTypeStyle> BY_KEY = Map.of(
        "BANK",            BANK,
        "CREDIT",          CREDIT,
        "CASH",            CASH,
        "SAVINGS",         SAVINGS,
        "VIRTUAL_WALLET",  VIRTUAL_WALLET,
        "DIGITAL_ACCOUNT", DIGITAL_ACCOUNT
    );

    // ══════════════════════════════════════════════════════════════════
    //  H E L P E R S
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns the canonical visual style for the given type key.
     * The key is normalised automatically via {@link AccountRepository#normalizeType(String)}.
     */
    public static AccountTypeStyle getStyle(String typeKey) {
        String normalised = AccountRepository.normalizeType(typeKey);
        return BY_KEY.getOrDefault(normalised, DEFAULT);
    }

    /**
     * Returns the canonical visual style for the given account.
     */
    public static AccountTypeStyle getStyle(AccountRepository.Account account) {
        if (account == null) return DEFAULT;
        return getStyle(account.type());
    }

    /**
     * Resolves the colour for a specific account, honouring a user-stored
     * personalisation colour when present.
     */
    public static String resolveColor(String typeKey, String storedColor) {
        if (storedColor != null && !storedColor.isBlank()) {
            return storedColor;
        }
        return getStyle(typeKey).color();
    }

    /**
     * Resolves the colour for a specific account, honouring its stored colour.
     */
    public static String resolveColor(AccountRepository.Account account) {
        if (account == null) return DEFAULT.color();
        return resolveColor(account.type(), account.color());
    }

    /**
     * Returns the Ikonli icon literal for the given account type.
     */
    public static String resolveIcon(String typeKey) {
        return getStyle(typeKey).icon();
    }

    /**
     * Returns the Ikonli icon literal for the given account.
     */
    public static String resolveIcon(AccountRepository.Account account) {
        if (account == null) return DEFAULT.icon();
        return getStyle(account.type()).icon();
    }

    /**
     * Returns the human-readable label for the given account type.
     */
    public static String resolveLabel(String typeKey) {
        return getStyle(typeKey).label();
    }

    /**
     * Returns the human-readable label for the given account.
     */
    public static String resolveLabel(AccountRepository.Account account) {
        if (account == null) return DEFAULT.label();
        return getStyle(account.type()).label();
    }

    /**
     * Returns the soft/background colour for the given account type.
     */
    public static String resolveSoftColor(String typeKey) {
        return getStyle(typeKey).softColor();
    }

    /**
     * Returns the soft/background colour for the given account.
     */
    public static String resolveSoftColor(AccountRepository.Account account) {
        if (account == null) return DEFAULT.softColor();
        return getStyle(account.type()).softColor();
    }
}
