package net.rev.stealthandalert.util;

/** Observer-relative exposure. Each part retains its own fractional visibility. */
public record BodyExposure(double head, double torso, double legs) {
    public static final BodyExposure NONE = new BodyExposure(0, 0, 0);
    public static final BodyExposure FULL = new BodyExposure(1, 1, 1);

    public BodyExposure {
        head = Math.clamp(head, 0, 1);
        torso = Math.clamp(torso, 0, 1);
        legs = Math.clamp(legs, 0, 1);
    }

    public enum Grade { HIDDEN, LOW, MEDIUM, HIGH, FULL }

    public double fraction() {
        return head * 0.30 + torso * 0.50 + legs * 0.20;
    }

    public boolean visible() { return fraction() > 0; }

    public Grade grade() { return grade(fraction()); }
    public Grade headGrade() { return grade(head); }
    public Grade torsoGrade() { return grade(torso); }
    public Grade legsGrade() { return grade(legs); }

    public static Grade grade(double fraction) {
        if (fraction <= 0) return Grade.HIDDEN;
        if (fraction < 0.25) return Grade.LOW;
        if (fraction < 0.60) return Grade.MEDIUM;
        if (fraction < 0.95) return Grade.HIGH;
        return Grade.FULL;
    }
}
