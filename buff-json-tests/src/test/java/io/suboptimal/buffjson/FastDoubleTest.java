package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.FastDouble;

/**
 * {@link FastDouble} (Eisel-Lemire) must return, bit for bit, what
 * {@code Double.parseDouble} returns for {@code w * 10^q}. The algorithm rests
 * on a 128-bit table of powers of five, so the first test derives that table
 * again with exact arithmetic; the others compare results with the JDK,
 * including the values where rounding is decided by a single bit (halfway
 * cases), the subnormal range and the overflow boundary.
 */
class FastDoubleTest {

	private static final int SMALLEST = -342;
	private static final int LARGEST = 308;

	/**
	 * The reference table generator: {@code 5^q} (q >= 0) normalised to 128 bits,
	 * truncated; for q < 0 {@code floor(2^k / 5^-q) + 1} with k chosen to give 128
	 * bits (via 127 + z bits for the small negative powers, 2z + 128 and truncation
	 * for the others).
	 */
	private static BigInteger[] expectedTable() {
		BigInteger[] table = new BigInteger[LARGEST - SMALLEST + 1];
		BigInteger five = BigInteger.valueOf(5);
		BigInteger two128 = BigInteger.ONE.shiftLeft(128);
		for (int q = SMALLEST; q <= LARGEST; q++) {
			BigInteger c;
			if (q < 0) {
				BigInteger power = five.pow(-q);
				int z = power.bitLength(); // never a power of two, so 2^(z-1) < power < 2^z
				if (q >= -27) {
					c = BigInteger.ONE.shiftLeft(z + 127).divide(power).add(BigInteger.ONE);
				} else {
					c = BigInteger.ONE.shiftLeft(2 * z + 128).divide(power).add(BigInteger.ONE);
					while (c.compareTo(two128) >= 0) {
						c = c.shiftRight(1);
					}
				}
			} else {
				BigInteger power = five.pow(q);
				int bits = power.bitLength();
				c = bits <= 128 ? power.shiftLeft(128 - bits) : power.shiftRight(bits - 128);
			}
			table[q - SMALLEST] = c;
		}
		return table;
	}

	@Test
	void powersOfFiveTableIsTheReferenceTable() {
		long[] actual = FastDouble.powersOfFive();
		BigInteger[] expected = expectedTable();
		assertEquals(2 * expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			BigInteger got = new BigInteger(Long.toUnsignedString(actual[2 * i])).shiftLeft(64)
					.add(new BigInteger(Long.toUnsignedString(actual[2 * i + 1])));
			assertEquals(expected[i], got, "5^" + (i + SMALLEST));
			assertTrue(got.testBit(127), "normalised: top bit set for q=" + (i + SMALLEST));
		}
	}

	private static void assertSameAsJdk(long w, int q) {
		double expected = Double.parseDouble(Long.toUnsignedString(w) + "e" + q);
		double actual = FastDouble.toDouble(w, q);
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
				Long.toUnsignedString(w) + "e" + q + ": expected " + expected + " but was " + actual);
	}

	@Test
	void everyExponentWithRandomSignificandsOfEveryLength() {
		Random rng = new Random(1);
		for (int q = SMALLEST - 3; q <= LARGEST + 3; q++) {
			for (int i = 0; i < 400; i++) {
				assertSameAsJdk(randomSignificand(rng, 1 + rng.nextInt(19)), q);
			}
		}
	}

	static long randomSignificand(Random rng, int digits) {
		BigInteger low = BigInteger.TEN.pow(digits - 1);
		BigInteger range = BigInteger.TEN.pow(digits).subtract(low);
		return new BigInteger(range.bitLength() + 16, rng).mod(range).add(low).longValue();
	}

	@Test
	void shortestFormsOfRandomDoublesRoundTrip() {
		Random rng = new Random(2);
		int checked = 0;
		for (int i = 0; i < 400_000; i++) {
			double d = Double.longBitsToDouble(rng.nextLong() & 0x7FFF_FFFF_FFFF_FFFFL);
			if (Double.isNaN(d) || Double.isInfinite(d) || d == 0) {
				continue;
			}
			BigDecimal decimal = new BigDecimal(Double.toString(d));
			long w = decimal.unscaledValue().longValue();
			if (w <= 0) {
				continue;
			}
			double actual = FastDouble.toDouble(w, -decimal.scale());
			assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(actual), Double.toString(d));
			checked++;
		}
		assertTrue(checked > 300_000);
	}

	@Test
	void halfwayCasesAreRoundedToEvenAndTheirNeighboursCorrectly() {
		Random rng = new Random(3);
		// integers above 2^53 that lie exactly between two doubles, and one above /
		// below
		for (int i = 0; i < 300_000; i++) {
			int e = 53 + rng.nextInt(10);
			long significand = (1L << 52) | (rng.nextLong() & ((1L << 52) - 1));
			long halfway = (significand << (e - 52)) + (1L << (e - 53));
			if (halfway <= 0 || Long.compareUnsigned(halfway, Long.parseUnsignedLong("9999999999999999999")) > 0) {
				continue;
			}
			assertSameAsJdk(halfway, 0);
			assertSameAsJdk(halfway - 1, 0);
			assertSameAsJdk(halfway + 1, 0);
		}
		// midpoints between adjacent doubles that have at most 19 significant digits
		int exact = 0;
		for (int i = 0; i < 200_000; i++) {
			double a = Double.longBitsToDouble(rng.nextLong() & 0x7FEF_FFFF_FFFF_FFFFL);
			double b = Math.nextUp(a);
			if (a == 0 || Double.isInfinite(b)) {
				continue;
			}
			BigDecimal mid = new BigDecimal(a).add(new BigDecimal(b)).divide(BigDecimal.valueOf(2))
					.stripTrailingZeros();
			if (mid.precision() <= 19) {
				long w = mid.unscaledValue().longValue();
				assertSameAsJdk(w, -mid.scale());
				assertSameAsJdk(w + 1, -mid.scale());
				if (w > 1) {
					assertSameAsJdk(w - 1, -mid.scale());
				}
				exact++;
			}
		}
		assertTrue(exact > 0);
	}

	@Test
	void boundaryValues() {
		long[] significands = {1, 9, 10, 9007199254740991L, 9007199254740992L, 9007199254740993L, 9007199254740995L,
				Long.MAX_VALUE, Long.MIN_VALUE, // 2^63 as unsigned
				Long.parseUnsignedLong("9999999999999999999"), 4503599627370497L, 2225073858507201L, 2225073858507202L,
				17976931348623157L, 17976931348623158L, 49406564584124654L, 24703282292062327L, 24703282292062328L};
		for (long w : significands) {
			for (int q = SMALLEST - 3; q <= LARGEST + 3; q++) {
				assertSameAsJdk(w, q);
			}
		}
	}

	@Test
	void overflowAndUnderflow() {
		assertEquals(Double.POSITIVE_INFINITY, FastDouble.toDouble(1, 309));
		assertEquals(Double.POSITIVE_INFINITY, FastDouble.toDouble(17976931348623159L, 292));
		assertEquals(Double.MAX_VALUE, FastDouble.toDouble(17976931348623157L, 292));
		assertEquals(0.0, FastDouble.toDouble(1, -400));
		assertEquals(Double.MIN_VALUE, FastDouble.toDouble(5, -324));
		assertEquals(0.0, FastDouble.toDouble(2, -324)); // just below half of the smallest subnormal
	}
}
