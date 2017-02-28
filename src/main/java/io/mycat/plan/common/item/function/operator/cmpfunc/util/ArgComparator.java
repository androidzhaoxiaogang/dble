package io.mycat.plan.common.item.function.operator.cmpfunc.util;

import java.math.BigDecimal;
import java.math.BigInteger;

import io.mycat.plan.common.MySQLcom;
import io.mycat.plan.common.item.FieldTypes;
import io.mycat.plan.common.item.Item;
import io.mycat.plan.common.item.Item.ItemResult;
import io.mycat.plan.common.item.function.ItemFunc;
import io.mycat.plan.common.item.function.operator.cmpfunc.ItemFuncStrictEqual;
import io.mycat.plan.common.ptr.BoolPtr;
import io.mycat.plan.common.ptr.LongPtr;
import io.mycat.plan.common.time.MySQLTimestampType;


public class ArgComparator {
	private Item a, b;
	private ItemFunc owner;
	private argCmpFunc func; // compare function name,在mysql源代码中为函数指针
	double precision = 0.0;
	/* Fields used in DATE/DATETIME comparison. */
	FieldTypes atype, btype; // Types of a and b items
	boolean is_nulls_eq; // TRUE <=> compare for the EQUAL_FUNC
	boolean setNull = true; // TRUE <=> set owner->null_value
	// when one of arguments is NULL.
	GetValueFunc getValueAFunc; // get_value_a_func name
	GetValueFunc getValueBFunc; // get_value_b_func name

	boolean try_year_cmp_func(ItemResult type) {
		if (type == ItemResult.ROW_RESULT)
			return false;
		boolean aisyear = a.fieldType() == FieldTypes.MYSQL_TYPE_YEAR;
		boolean bisyear = b.fieldType() == FieldTypes.MYSQL_TYPE_YEAR;
		if (!aisyear && !bisyear)
			return false;
		if (aisyear && bisyear) {
			getValueAFunc = new GetYearValue();
			getValueBFunc = new GetYearValue();
		} else if (aisyear && b.isTemporalWithDate()) {
			getValueAFunc = new GetYearValue();
			getValueBFunc = new GetDatetimeValue();
		} else if (bisyear && a.isTemporalWithDate()) {
			getValueBFunc = new GetYearValue();
			getValueAFunc = new GetDatetimeValue();
		} else
			return false;
		is_nulls_eq = isOwnerEqualFunc();
		func = new CompareDatetime();
		setcmpcontextfordatetime();
		return true;
	}

	/**
	 * Check if str_arg is a constant and convert it to datetime packed value.
	 * Note, const_value may stay untouched, so the caller is responsible to
	 * initialize it.
	 * 
	 * @param dateArg
	 *            date argument, it's name is used for error reporting.
	 * @param strArg
	 *            string argument to get datetime value from.
	 * @param[out] const_value the converted value is stored here, if not NULL.
	 * @return true on error, false on success, false if str_arg is not a const.
	 */
	static boolean getDateFromConst(Item dateArg, Item strArg, LongPtr constValue) {
		BoolPtr error = new BoolPtr(false);
		long value = 0;
		if (strArg.fieldType() == FieldTypes.MYSQL_TYPE_TIME) {
			// Convert from TIME to DATETIME
			value = strArg.valDateTemporal();
			if (strArg.nullValue)
				return true;
		} else {
			// Convert from string to DATETIME
			String strVal = strArg.valStr();
			MySQLTimestampType ttype = (dateArg.fieldType() == FieldTypes.MYSQL_TYPE_DATE
					? MySQLTimestampType.MYSQL_TIMESTAMP_DATE
					: MySQLTimestampType.MYSQL_TIMESTAMP_DATETIME);
			if (strArg.nullValue) {
				return true;
			}
			value = MySQLcom.get_date_from_str(strVal, ttype, error);
			if (error.get())
				return true;
		}
		if (constValue != null)
			constValue.set(value);
		return false;
	}

	public ArgComparator() {

	}

	public ArgComparator(Item a, Item b) {
		this.a = a;
		this.b = b;
	}

	public int setCompareFunc(ItemFunc ownerarg, ItemResult type) {
		owner = ownerarg;
		func = comparator_matrix[type.ordinal()][isOwnerEqualFunc() == true ? 1 : 0];
		switch (type) {
		case ROW_RESULT:
			// 未实现
			return 1;
		case STRING_RESULT: {
			if (func instanceof CompareString)
				func = new CompareBinaryString();
			else if (func instanceof CompareEString)
				func = new CompareEBinaryString();
			break;
		}
		case INT_RESULT: {
			if (a.isTemporal() && b.isTemporal()) {
				func = isOwnerEqualFunc() ? new CompareETimePacked() : new CompareTimePacked();
			} else if (func instanceof CompareIntSigned) {
				//
			} else if (func instanceof CompareEInt) {
				//
			}
			break;
		}
		case DECIMAL_RESULT:
			break;
		case REAL_RESULT: {
			if (a.decimals < Item.NOT_FIXED_DEC && b.decimals < Item.NOT_FIXED_DEC) {
				precision = 5 / Math.pow(10, (Math.max(a.decimals, b.decimals) + 1));
				if (func instanceof CompareReal)
					func = new CompareRealFixed();
				else if (func instanceof CompareEReal)
					func = new CompareERealFixed();
			}
			break;
		}
		default:
		}
		return 0;
	}

	public int setCompareFunc(ItemFunc ownerarg) {
		return setCompareFunc(ownerarg, MySQLcom.item_cmp_type(a.resultType(), b.resultType()));
	}

	public int setCmpFunc(ItemFunc ownerarg, Item a1, Item a2, ItemResult type) {
		LongPtr constvalue = new LongPtr(-1);
		owner = ownerarg;
		setNull = setNull && (ownerarg != null);
		a = a1;
		b = a2;
		if (canCompareAsDates(a, b, constvalue)) {
			atype = a.fieldType();
			btype = b.fieldType();
			is_nulls_eq = isOwnerEqualFunc();
			func = new CompareDatetime();
			getValueAFunc = new GetDatetimeValue();
			getValueBFunc = new GetDatetimeValue();
			setcmpcontextfordatetime();
			return 0;
		} else if (type == ItemResult.STRING_RESULT && a.fieldType() == FieldTypes.MYSQL_TYPE_TIME
				&& b.fieldType() == FieldTypes.MYSQL_TYPE_TIME) {
			is_nulls_eq = isOwnerEqualFunc();
			func = new CompareDatetime();
			getValueAFunc = new GetTimeValue();
			getValueBFunc = new GetTimeValue();
			setcmpcontextfordatetime();
			return 0;
		} else if (type == ItemResult.STRING_RESULT && a.resultType() == ItemResult.STRING_RESULT
				&& b.resultType() == ItemResult.STRING_RESULT) {
			// see item_cmpfunc.cc line1054
		} else if (try_year_cmp_func(type)) {
			return 0;
		}
		return setCompareFunc(ownerarg, type);
	}

	public int setCmpFunc(ItemFunc ownerarg, Item a1, Item a2, boolean setnullarg) {
		setNull = setnullarg;
		return setCmpFunc(ownerarg, a1, a2, MySQLcom.item_cmp_type(a1.resultType(), a2.resultType()));
	}

	public int compare() {
		return this.func.compare(this);
	}

	public boolean isOwnerEqualFunc() {
		if (this.owner != null)
			return this.owner instanceof ItemFuncStrictEqual;
		return false;
	}

	public void setDatetimeCmpFunc(ItemFunc ownerArg, Item a1, Item a2) {
		owner = ownerArg;
		a = a1;
		b = a2;
		atype = a.fieldType();
		btype = b.fieldType();
		is_nulls_eq = false;
		func = new CompareDatetime();
		getValueAFunc = new GetDatetimeValue();
		getValueBFunc = new GetDatetimeValue();
		setcmpcontextfordatetime();
	}

	/*
	 * Check whether compare_datetime() can be used to compare items.
	 * 
	 * SYNOPSIS Arg_comparator::can_compare_as_dates() a, b [in] items to be
	 * compared const_value [out] converted value of the string constant, if any
	 * 
	 * DESCRIPTION Check several cases when the DATE/DATETIME comparator should
	 * be used. The following cases are checked: 1. Both a and b is a
	 * DATE/DATETIME field/function returning string or int result. 2. Only a or
	 * b is a DATE/DATETIME field/function returning string or int result and
	 * the other item (b or a) is an item with string result. If the second item
	 * is a constant one then it's checked to be convertible to the
	 * DATE/DATETIME type. If the constant can't be converted to a DATE/DATETIME
	 * then the compare_datetime() comparator isn't used and the warning about
	 * wrong DATE/DATETIME value is issued. In all other cases
	 * (date-[int|real|decimal]/[int|real|decimal]-date) the comparison is
	 * handled by other comparators. If the datetime comparator can be used and
	 * one the operands of the comparison is a string constant that was
	 * successfully converted to a DATE/DATETIME type then the result of the
	 * conversion is returned in the const_value if it is provided. If there is
	 * no constant or compare_datetime() isn't applicable then the *const_value
	 * remains unchanged.
	 * 
	 * @return true if can compare as dates, false otherwise.
	 */
	public static boolean canCompareAsDates(Item a, Item b, LongPtr constvalue) {
		if (a.isTemporalWithDate()) {
			if (b.isTemporalWithDate())// date[time] + date
			{
				return true;
			} else if (b.resultType() == ItemResult.STRING_RESULT) {// date[time]
																		// +
				// string
				return !getDateFromConst(a, b, constvalue);
			} else
				return false;
		} else if (b.isTemporalWithDate() && a.resultType() == ItemResult.STRING_RESULT) // string
																								// +
		// date[time]
		{
			return !getDateFromConst(b, a, constvalue);
		} else
			return false;// No date[time] items found
	}

	public static argCmpFunc[][] comparator_matrix = { { new CompareString(), new CompareEString() },
			{ new CompareReal(), new CompareEReal() }, { new CompareIntSigned(), new CompareEInt() },
			{ new CompareRow(), new CompareERow() }, { new CompareDecimal(), new CompareEDecimal() } };

	public void setcmpcontextfordatetime() {
		if (a.isTemporal())
			a.cmpContext = ItemResult.INT_RESULT;
		if (b.isTemporal())
			b.cmpContext = ItemResult.INT_RESULT;
	}

	/**
	 * compare function
	 * 
	 * @author chenzifei
	 * 
	 */
	private static interface argCmpFunc {
		int compare(ArgComparator ac);
	}

	private static class CompareString implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			String res1, res2;
			if ((res1 = ac.a.valStr()) != null) {
				if ((res2 = ac.b.valStr()) != null) {
					if (ac.setNull && ac.owner != null) {
						ac.owner.nullValue = false;
						return res1.compareTo(res2);
					}
				}
			}
			if (ac.setNull)
				ac.owner.nullValue = true;
			return ac.a.nullValue ? -1 : 1;
		}
	}

	private static class CompareBinaryString implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			String res1, res2;
			if ((res1 = ac.a.valStr()) != null) {
				if ((res2 = ac.b.valStr()) != null) {
					if (ac.setNull && ac.owner != null)
						ac.owner.nullValue = (false);
					byte[] res1b = res1.getBytes();
					byte[] res2b = res2.getBytes();
					int res1Len = res1b.length;
					int res2Len = res2b.length;
					int cmp = MySQLcom.memcmp(res1b, res2b, Math.min(res1Len, res2Len));
					return cmp != 0 ? cmp : (int) (res1Len - res2Len);
				}
			}
			if (ac.setNull)
				ac.owner.nullValue = (true);
			return ac.a.nullValue ? -1 : 1;

		}
	}

	private static class CompareReal implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			BigDecimal val1, val2;
			val1 = ac.a.valReal();
			if (!(ac.a.isNull())) {
				val2 = ac.b.valReal();
				if (!(ac.b.isNull())) {
					if (ac.setNull && ac.owner != null)
						ac.owner.nullValue = (false);
					if (val1.compareTo(val2) < 0)
						return -1;
					if (val1.compareTo(val2) == 0)
						return 0;
					return 1;
				}
			}
			if (ac.setNull)
				ac.owner.nullValue = true;
			return ac.a.nullValue ? -1 : 1;
		}
	}

	private static class CompareDecimal implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			BigDecimal val1 = ac.a.valDecimal();
			if (!ac.a.isNull()) {
				BigDecimal val2 = ac.b.valDecimal();
				if (!ac.b.isNull()) {
					if (ac.setNull && ac.owner != null)
						ac.owner.nullValue = (false);
					return val1.compareTo(val2);
				}
			}
			if (ac.setNull)
				ac.owner.nullValue = (true);
			return ac.a.nullValue ? -1 : 1;
		}
	}

	private static class CompareIntSigned implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			BigInteger val1 = ac.a.valInt();
			if (!ac.a.isNull()) {
				BigInteger val2 = ac.b.valInt();
				if (!ac.b.isNull()) {
					if (ac.setNull && ac.owner != null)
						ac.owner.nullValue = (false);
					if (val1.compareTo(val2) < 0)
						return -1;
					if (val1.compareTo(val2) == 0)
						return 0;
					return 1;
				}
			}
			if (ac.setNull)
				ac.owner.nullValue = (true);
			return ac.a.nullValue ? -1 : 1;
		}
	}

	/**
	 * Compare arguments using numeric packed temporal representation.
	 */
	private static class CompareTimePacked implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			/*
			 * Note, we cannot do this: DBUG_ASSERT((*a)->field_type() ==
			 * MYSQL_TYPE_TIME); DBUG_ASSERT((*b)->field_type() ==
			 * MYSQL_TYPE_TIME);
			 * 
			 * SELECT col_time_key FROM t1 WHERE col_time_key != UTC_DATE() AND
			 * col_time_key = MAKEDATE(43, -2852);
			 * 
			 * is rewritten to:
			 * 
			 * SELECT col_time_key FROM t1 WHERE MAKEDATE(43, -2852) !=
			 * UTC_DATE() AND col_time_key = MAKEDATE(43, -2852);
			 */
			long val1 = ac.a.valDateTemporal();
			if (!ac.a.isNull()) {
				long val2 = ac.b.valDateTemporal();
				if (!ac.b.isNull()) {
					if (ac.setNull && ac.owner != null)
						ac.owner.nullValue = (false);
					return val1 < val2 ? -1 : val1 > val2 ? 1 : 0;
				}
			}
			if (ac.setNull)
				ac.owner.nullValue = (true);
			return ac.a.nullValue ? -1 : 1;
		}
	}

	private static class CompareETimePacked implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			long val1 = ac.a.valDateTemporal();
			long val2 = ac.b.valDateTemporal();
			if (ac.a.isNull() || ac.b.isNull())
				return (ac.a.isNull() && ac.b.isNull()) ? 1 : 0;
			return (val1 == val2) ? 1 : 0;
		}
	}

	private static class CompareRow implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			// TODO Auto-generated method stub
			return 0;
		}
	}

	private static class CompareEString implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			String res1, res2;
			res1 = ac.a.valStr();
			res2 = ac.b.valStr();
			if (res1 == null || res2 == null)
				return (res1 == res2) ? 1 : 0;
			return (res1.compareTo(res2) == 0) ? 1 : 0;
		}
	}

	private static class CompareEBinaryString implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			String res1, res2;
			res1 = ac.a.valStr();
			res2 = ac.b.valStr();
			if (res1 == null || res2 == null)
				return (res1 == res2) ? 1 : 0;
			return MySQLcom.memcmp(res1.getBytes(), res2.getBytes(), Math.min(res1.length(), res2.length())) == 0 ? 1
					: 0;
		}
	}

	private static class CompareEReal implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			BigDecimal val1 = ac.a.valReal();
			BigDecimal val2 = ac.b.valReal();
			if (ac.a.isNull() || ac.b.isNull())
				return (ac.a.isNull() && ac.b.isNull()) ? 1 : 0;
			return val1.compareTo(val2) == 0 ? 1 : 0;
		}
	}

	private static class CompareEDecimal implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			BigDecimal val1 = ac.a.valDecimal();
			BigDecimal val2 = ac.b.valDecimal();
			if (ac.a.isNull() || ac.b.isNull())
				return (ac.a.isNull() && ac.b.isNull()) ? 1 : 0;
			return (val1.compareTo(val2) == 0) ? 1 : 0;
		}
	}

	private static class CompareEInt implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			BigInteger val1 = ac.a.valInt();
			BigInteger val2 = ac.b.valInt();
			if (ac.a.isNull() || ac.b.isNull())
				return (ac.a.isNull() && ac.b.isNull()) ? 1 : 0;
			return val1.compareTo(val2) == 0 ? 1 : 0;
		}
	}

	private static class CompareERow implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			// TODO Auto-generated method stub
			return 0;
		}
	}

	private static class CompareRealFixed implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			/*
			 * Fix yet another manifestation of Bug#2338. 'Volatile' will
			 * instruct gcc to flush double values out of 80-bit Intel FPU
			 * registers before performing the comparison.
			 */
			BigDecimal val1, val2;
			val1 = ac.a.valReal();
			if (!ac.a.isNull()) {
				val2 = ac.b.valReal();
				if (!ac.b.isNull()) {
					if (ac.setNull && ac.owner != null)
						ac.owner.nullValue = (false);
					if (val1.compareTo(val2) == 0 || Math.abs(val1.doubleValue() - val2.doubleValue()) < ac.precision)
						return 0;
					if (val1.compareTo(val2) < 0)
						return -1;
					return 1;
				}
			}
			if (ac.setNull)
				ac.owner.nullValue = (true);
			return ac.a.nullValue ? -1 : 1;
		}
	}

	private static class CompareERealFixed implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			double val1 = ac.a.valReal().doubleValue();
			double val2 = ac.b.valReal().doubleValue();
			if (ac.a.isNull() || ac.b.isNull())
				return (ac.a.isNull() && ac.b.isNull()) ? 1 : 0;
			return (val1 == val2 || Math.abs(val1 - val2) < ac.precision) ? 1 : 0;
		}
	}

	/**
	 * compare args[0] & args[1] as DATETIMEs SYNOPSIS
	 * Arg_comparator::compare_datetime()
	 * 
	 * DESCRIPTION Compare items values as DATE/DATETIME for both EQUAL_FUNC and
	 * from other comparison functions. The correct DATETIME values are obtained
	 * with help of the get_datetime_value() function.
	 * 
	 * RETURN If is_nulls_eq is TRUE: 1 if items are equal or both are null 0
	 * otherwise If is_nulls_eq is FALSE: -1 a < b or at least one item is null
	 * 0 a == b 1 a > b See the table: is_nulls_eq | 1 | 1 | 1 | 1 | 0 | 0 | 0 |
	 * 0 | a_is_null | 1 | 0 | 1 | 0 | 1 | 0 | 1 | 0 | b_is_null | 1 | 1 | 0 | 0
	 * | 1 | 1 | 0 | 0 | result | 1 | 0 | 0 |0/1|-1 |-1 |-1 |-1/0/1|
	 * 
	 * @author chenzifei
	 * 
	 */
	private static class CompareDatetime implements argCmpFunc {

		@Override
		public int compare(ArgComparator ac) {
			BoolPtr aIsNull = new BoolPtr(false);
			BoolPtr bIsNull = new BoolPtr(false);
			long a_value, b_value;

			/* Get DATE/DATETIME/TIME value of the 'a' item. */
			a_value = ac.getValueAFunc.get(ac.a, ac.b, aIsNull);
			if (!ac.is_nulls_eq && aIsNull.get()) {
				if (ac.setNull && ac.owner != null)
					ac.owner.nullValue = (true);
				return -1;
			}

			/* Get DATE/DATETIME/TIME value of the 'b' item. */
			b_value = ac.getValueBFunc.get(ac.b, ac.a, bIsNull);
			if (aIsNull.get() || bIsNull.get()) {
				if (ac.setNull)
					ac.owner.nullValue = (ac.is_nulls_eq ? false : true);
				return ac.is_nulls_eq ? (aIsNull.get() == bIsNull.get()) ? 1 : 0 : -1;
			}

			/* Here we have two not-NULL values. */
			if (ac.setNull)
				ac.owner.nullValue = (false);

			/* Compare values. */
			if (ac.is_nulls_eq)
				return a_value == (b_value) ? 1 : 0;
			return a_value < b_value ? -1 : (a_value > b_value ? 1 : 0);
		}
	}

}