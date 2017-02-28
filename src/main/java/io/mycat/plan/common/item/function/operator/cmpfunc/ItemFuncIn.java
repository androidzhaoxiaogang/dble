package io.mycat.plan.common.item.function.operator.cmpfunc;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;

import io.mycat.plan.common.MySQLcom;
import io.mycat.plan.common.field.Field;
import io.mycat.plan.common.item.Item;
import io.mycat.plan.common.item.function.operator.cmpfunc.util.ArgComparator;


public class ItemFuncIn extends ItemFuncOptNeg {
	private ItemResult left_result_type;
	private boolean have_null = false;

	/**
	 * select 'a' in ('a','b','c') args(0)为'a',[1]为'a',[2]为'b'。。。
	 * 
	 * @param args
	 */
	public ItemFuncIn(List<Item> args, boolean isNegation) {
		super(args, isNegation);
	}

	@Override
	public final String funcName() {
		return "in";
	}

	@Override
	public void fixLengthAndDec() {
		for (int i = 1; i < args.size(); i++) {
			args.get(i).cmpContext = MySQLcom.item_cmp_type(left_result_type, args.get(i).resultType());
		}
		maxLength = 1;
	}

	@Override
	public BigInteger valInt() {
		if ((nullValue = args.get(0).type() == Item.ItemType.NULL_ITEM))
			return BigInteger.ZERO;
		Item left = args.get(0);
		if (nullValue = left.type() == ItemType.NULL_ITEM) {
			return BigInteger.ZERO;
		}
		have_null = false;
		for (int i = 1; i < args.size(); i++) {
			Item right = args.get(i);
			if (right.type() == ItemType.NULL_ITEM) {
				have_null = true;
				continue;
			}
			left.valInt();
			if (nullValue = left.nullValue)
				return BigInteger.ZERO;
			ArgComparator cmp = new ArgComparator(left, right);
			cmp.setCmpFunc(this, left, right, false);
			if (cmp.compare() == 0 && !right.nullValue)
				return !negated ? BigInteger.ONE : BigInteger.ZERO;
			have_null |= right.isNull();
		}
		nullValue = have_null;
		return (!nullValue && negated) ? BigInteger.ONE : BigInteger.ZERO;
	}
	
	@Override
	public SQLExpr toExpression() {
		SQLInListExpr in = new SQLInListExpr(args.get(0).toExpression(), this.negated);
		List<SQLExpr> targetList = new ArrayList<SQLExpr>();
		int index = 0;
		for (Item item : args) {
			if (index != 0) {
				targetList.add(item.toExpression());
			}
			index++;
		}
		in.setTargetList(targetList);
		return in;
	}

	@Override
	protected Item cloneStruct(boolean forCalculate, List<Item> calArgs, boolean isPushDown, List<Field> fields) {
		List<Item> newArgs = null;
		if (!forCalculate)
			newArgs = cloneStructList(args);
		else
			newArgs = calArgs;
		return new ItemFuncIn(newArgs, this.negated);
	}

}