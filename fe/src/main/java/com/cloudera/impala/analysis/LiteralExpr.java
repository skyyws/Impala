// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloudera.impala.analysis;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.cloudera.impala.catalog.ScalarType;
import com.cloudera.impala.catalog.Type;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.NotImplementedException;
import com.cloudera.impala.service.FeSupport;
import com.cloudera.impala.thrift.TColumnValue;
import com.cloudera.impala.thrift.TExprNode;
import com.cloudera.impala.thrift.TQueryCtx;
import com.google.common.base.Preconditions;

/**
 * Representation of a literal expression. Literals are comparable to allow
 * ordering of HdfsPartitions whose partition-key values are represented as literals.
 */
public abstract class LiteralExpr extends Expr implements Comparable<LiteralExpr> {

  public LiteralExpr() {
    numDistinctValues_ = 1;
  }

  /**
   * Copy c'tor used in clone().
   */
  protected LiteralExpr(LiteralExpr other) {
    super(other);
  }

  /**
   * Returns an analyzed literal of 'type'.
   */
  public static LiteralExpr create(String value, Type type) throws AnalysisException {
    Preconditions.checkArgument(type.isValid());
    LiteralExpr e = null;
    switch (type.getPrimitiveType()) {
      case NULL_TYPE:
        e = new NullLiteral();
        break;
      case BOOLEAN:
        e = new BoolLiteral(value);
        break;
      case TINYINT:
      case SMALLINT:
      case INT:
      case BIGINT:
      case FLOAT:
      case DOUBLE:
      case DECIMAL:
        e = new NumericLiteral(value, type);
        break;
      case STRING:
      case VARCHAR:
      case CHAR:
        e = new StringLiteral(value);
        break;
      case DATE:
      case DATETIME:
      case TIMESTAMP:
        // TODO: we support TIMESTAMP but no way to specify it in SQL.
        throw new AnalysisException(
            "DATE/DATETIME/TIMESTAMP literals not supported: " + value);
      default:
        Preconditions.checkState(false,
            String.format("Literals of type '%s' not supported.", type.toSql()));
    }
    e.analyze(null);
    // Need to cast since we cannot infer the type from the value. e.g. value
    // can be parsed as tinyint but we need a bigint.
    return (LiteralExpr) e.uncheckedCastTo(type);
  }

  /**
   * Returns an analyzed literal from the thrift object.
   */
  public static LiteralExpr fromThrift(TExprNode exprNode, Type colType) {
    try {
      LiteralExpr result = null;
      switch (exprNode.node_type) {
        case FLOAT_LITERAL:
          result = LiteralExpr.create(
              Double.toString(exprNode.float_literal.value), colType);
          break;
        case DECIMAL_LITERAL:
          byte[] bytes = exprNode.decimal_literal.getValue();
          BigDecimal val = new BigDecimal(new BigInteger(bytes));
          ScalarType decimalType = (ScalarType) colType;
          // We store the decimal as the unscaled bytes. Need to adjust for the scale.
          val = val.movePointLeft(decimalType.decimalScale());
          result = new NumericLiteral(val, colType);
          break;
        case INT_LITERAL:
          result = LiteralExpr.create(
              Long.toString(exprNode.int_literal.value), colType);
          break;
        case STRING_LITERAL:
          result = LiteralExpr.create(exprNode.string_literal.value, colType);
          break;
        case BOOL_LITERAL:
          result =  LiteralExpr.create(
              Boolean.toString(exprNode.bool_literal.value), colType);
          break;
        case NULL_LITERAL:
          return NullLiteral.create(colType);
        default:
          throw new UnsupportedOperationException("Unsupported partition key type: " +
              exprNode.node_type);
      }
      Preconditions.checkNotNull(result);
      result.analyze(null);
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("Error creating LiteralExpr: ", e);
    }
  }

  // Returns the string representation of the literal's value. Used when passing
  // literal values to the metastore rather than to Impala backends. This is similar to
  // the toSql() method, but does not perform any formatting of the string values. Neither
  // method unescapes string values.
  public abstract String getStringValue();

  // Swaps the sign of numeric literals.
  // Throws for non-numeric literals.
  public void swapSign() throws NotImplementedException {
    throw new NotImplementedException("swapSign() only implemented for numeric" +
        "literals");
  }

  /**
   * Evaluates the given constant expr and returns its result as a LiteralExpr.
   * Assumes expr has been analyzed. Returns constExpr if is it already a LiteralExpr.
   * TODO: Support non-scalar types.
   */
  public static LiteralExpr create(Expr constExpr, TQueryCtx queryCtx)
      throws AnalysisException {
    Preconditions.checkState(constExpr.isConstant());
    Preconditions.checkState(constExpr.getType().isValid());
    if (constExpr instanceof LiteralExpr) return (LiteralExpr) constExpr;

    TColumnValue val = null;
    try {
      val = FeSupport.EvalConstExpr(constExpr, queryCtx);
    } catch (InternalException e) {
      throw new AnalysisException(String.format("Failed to evaluate expr '%s'",
          constExpr.toSql()), e);
    }

    LiteralExpr result = null;
    switch (constExpr.getType().getPrimitiveType()) {
      case NULL_TYPE:
        result = new NullLiteral();
        break;
      case BOOLEAN:
        if (val.isBool_val()) result = new BoolLiteral(val.bool_val);
        break;
      case TINYINT:
        if (val.isSetByte_val()) {
          result = new NumericLiteral(BigDecimal.valueOf(val.byte_val));
        }
        break;
      case SMALLINT:
        if (val.isSetShort_val()) {
          result = new NumericLiteral(BigDecimal.valueOf(val.short_val));
        }
        break;
      case INT:
        if (val.isSetInt_val()) {
          result = new NumericLiteral(BigDecimal.valueOf(val.int_val));
        }
        break;
      case BIGINT:
        if (val.isSetLong_val()) {
          result = new NumericLiteral(BigDecimal.valueOf(val.long_val));
        }
        break;
      case FLOAT:
      case DOUBLE:
        if (val.isSetDouble_val()) {
          result =
              new NumericLiteral(new BigDecimal(val.double_val), constExpr.getType());
        }
        break;
      case DECIMAL:
        if (val.isSetString_val()) {
          result =
              new NumericLiteral(new BigDecimal(val.string_val), constExpr.getType());
        }
        break;
      case STRING:
      case VARCHAR:
      case CHAR:
        if (val.isSetString_val()) result = new StringLiteral(val.string_val);
        break;
      case DATE:
      case DATETIME:
      case TIMESTAMP:
        throw new AnalysisException(
            "DATE/DATETIME/TIMESTAMP literals not supported: " + constExpr.toSql());
      default:
        Preconditions.checkState(false,
            String.format("Literals of type '%s' not supported.",
                constExpr.getType().toSql()));
    }
    // None of the fields in the thrift struct were set indicating a NULL.
    if (result == null) result = new NullLiteral();

    result.analyze(null);
    return (LiteralExpr)result;
  }

  // Order NullLiterals based on the SQL ORDER BY default behavior: NULLS LAST.
  @Override
  public int compareTo(LiteralExpr other) {
    if (this instanceof NullLiteral && other instanceof NullLiteral) return 0;
    if (this instanceof NullLiteral) return -1;
    if (other instanceof NullLiteral) return 1;
    if (getClass() != other.getClass()) return -1;
    return 0;
  }
}
