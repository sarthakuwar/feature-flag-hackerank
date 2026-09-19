package com.featureflag.flags.targeting;

/**
 * A view of a string that counts every {@link #charAt} call and throws once a fixed budget is
 * spent. The regex engine reads its input only through {@code charAt}, so the count is a
 * deterministic measure of matching work: unlike a clock or a watchdog thread it gives the same
 * answer on every run, which keeps flag evaluation a pure function.
 */
final class BudgetedCharSequence implements CharSequence {

	/** Thrown when the budget is spent; carries no stack trace because it is expected control flow. */
	static final class BudgetExceededException extends RuntimeException {

		BudgetExceededException() {
			super("Regex step budget exceeded", null, false, false);
		}
	}

	private final CharSequence delegate;

	private long remaining;

	BudgetedCharSequence(CharSequence delegate, long budget) {
		this.delegate = delegate;
		this.remaining = budget;
	}

	@Override
	public char charAt(int index) {
		if (--remaining < 0) {
			throw new BudgetExceededException();
		}

		return delegate.charAt(index);
	}

	@Override
	public int length() {
		return delegate.length();
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return delegate.subSequence(start, end);
	}

	@Override
	public String toString() {
		return delegate.toString();
	}
}
