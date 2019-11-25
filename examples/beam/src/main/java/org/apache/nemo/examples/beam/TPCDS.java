/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.nemo.examples.beam;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.sql.SqlTransform;
import org.apache.beam.sdk.extensions.sql.meta.provider.text.TextTable;
import org.apache.beam.sdk.extensions.sql.meta.provider.text.TextTableProvider;
import org.apache.beam.sdk.io.parquet.ParquetIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TPC DS application.
 */
public final class TPCDS {
  /**
   * Private constructor.
   */
  private TPCDS() {
  }

  /**
   * @param args arguments.
   */
  public static void main(final String[] args) {
    final String inputFilePath = args[0];
    final String outputFilePath = args[1];

    final PipelineOptions options = NemoPipelineOptionsFactory.create();
    options.setJobName("TPCDS");
    final Pipeline p = Pipeline.create(options);

    final List<String> tables = Arrays.asList("date_dim", "store_sales", "item", "inventory",
      "catalog_sales", "customer", "promotion", "customer_demographics", "web_sales");
      // Arrays.asList("catalog_page", "catalog_returns", "customer", "customer_address",
      // "customer_demographics", "date_dim", "household_demographics", "inventory", "item",
      // "promotion", "store", "store_returns", "catalog_sales", "web_sales", "store_sales",
      // "web_returns", "web_site", "reason", "call_center", "warehouse", "ship_mode", "income_band",
      // "time_dim", "web_page");

    final Map<String, PCollection<Row>> tableMap = setupTables(p, inputFilePath, tables);

    PCollectionTuple tableTuple = PCollectionTuple.empty(p);
    for (Map.Entry<String, PCollection<Row>> e: tableMap.entrySet()) {
      tableTuple = tableTuple.and(new TupleTag<>(e.getKey()), e.getValue());
    }

    final PCollection<Row> res = tableTuple.apply(SqlTransform.query(QUERY3));
    GenericSourceSink.write(res.apply(MapElements.via(new SimpleFunction<Row, String>() {
      @Override
      public String apply(final Row input) {
        final String c2 = input.getString(0);
        final Double c3 = input.getDouble(1);
        return c2 + " is " + c3;
      }
    })), outputFilePath);
    p.run();
  }

  private static Map<String, Schema> setupSchema() {
    final Map<String, Schema> result = new HashMap<>();

    result.put("date_dim", dateDimSchema);
    result.put("store_sales", storeSalesSchema);
    result.put("item", itemSchema);
    result.put("inventory", inventorySchema);
    result.put("catalog_sales", catalogSalesSchema);
    result.put("customer", getCustomerDsSchema);
    result.put("promotion", promotionSchema);
    result.put("customer_demographics", customerDemographicsSchema);
    result.put("web_sales", webSalesSchema);

    return result;
  }

  private static Map<String, PCollection<Row>> setupTables(final Pipeline p, final String inputFilePath,
                                                           final List<String> tables) {
    final Map<String, PCollection<Row>> result = new HashMap<>();
    final Map<String, Schema> schemaMap = setupSchema();

    tables.forEach(t -> {
      final PCollection<Row> table = PBegin.in(p).apply(ParquetIO.read(schemaMap.get(t)).from(inputFilePath + t + "/*"));

      // final PCollection<Row> table =
      //   new TextTable(
      //     tableSchema.getValue(),
      //     filePattern,
      //     new TextTableProvider.CsvToRow(tableSchema.getValue(), csvFormat),
      //     new TextTableProvider.RowToCsv(csvFormat))
      //     .buildIOReader(pipeline.begin())
      //     .setCoder(tableSchema.getValue().getRowCoder())
      //     .setName(tableSchema.getKey());

      result.put(t, table);
    });



    return result;
  }

  private static Schema storeSalesSchema =
    Schema.builder()
      .addNullableField("ss_sold_date_sk", Schema.FieldType.INT32)
      .addNullableField("ss_sold_time_sk", Schema.FieldType.INT32)
      .addNullableField("ss_item_sk", Schema.FieldType.INT32)
      .addNullableField("ss_customer_sk", Schema.FieldType.STRING)
      .addNullableField("ss_cdemo_sk", Schema.FieldType.INT32)
      .addNullableField("ss_hdemo_sk", Schema.FieldType.INT32)
      .addNullableField("ss_addr_sk", Schema.FieldType.INT32)
      .addNullableField("ss_store_sk", Schema.FieldType.INT32)
      .addNullableField("ss_promo_sk", Schema.FieldType.INT32)
      .addNullableField("ss_ticket_number", Schema.FieldType.INT64)
      .addNullableField("ss_quantity", Schema.FieldType.INT32)
      .addNullableField("ss_wholesale_cost", Schema.FieldType.FLOAT)
      .addNullableField("ss_list_price", Schema.FieldType.FLOAT)
      .addNullableField("ss_sales_price", Schema.FieldType.FLOAT)
      .addNullableField("ss_ext_discount_amt", Schema.FieldType.FLOAT)
      .addNullableField("ss_ext_sales_price", Schema.FieldType.FLOAT)
      .addNullableField("ss_ext_wholesale_cost", Schema.FieldType.FLOAT)
      .addNullableField("ss_ext_list_price", Schema.FieldType.FLOAT)
      .addNullableField("ss_ext_tax", Schema.FieldType.FLOAT)
      .addNullableField("ss_coupon_amt", Schema.FieldType.FLOAT)
      .addNullableField("ss_net_paid", Schema.FieldType.FLOAT)
      .addNullableField("ss_net_paid_inc_tax", Schema.FieldType.FLOAT)
      .addNullableField("ss_net_profit", Schema.FieldType.FLOAT)
      .build();


  private static Schema dateDimSchema =
    Schema.builder()
      .addNullableField("d_date_sk", Schema.FieldType.INT32)
      .addNullableField("d_date_id", Schema.FieldType.STRING)
      .addNullableField("d_date", Schema.FieldType.STRING)
      .addNullableField("d_month_seq", Schema.FieldType.INT32)
      .addNullableField("d_week_seq", Schema.FieldType.INT32)
      .addNullableField("d_quarter_seq", Schema.FieldType.INT32)
      .addNullableField("d_year", Schema.FieldType.INT32)
      .addNullableField("d_dow", Schema.FieldType.INT32)
      .addNullableField("d_moy", Schema.FieldType.INT32)
      .addNullableField("d_dom", Schema.FieldType.INT32)
      .addNullableField("d_qoy", Schema.FieldType.INT32)
      .addNullableField("d_fy_year", Schema.FieldType.INT32)
      .addNullableField("d_fy_quarter_seq", Schema.FieldType.INT32)
      .addNullableField("d_fy_week_seq", Schema.FieldType.INT32)
      .addNullableField("d_day_name", Schema.FieldType.STRING)
      .addNullableField("d_quarter_name", Schema.FieldType.STRING)
      .addNullableField("d_holiday", Schema.FieldType.STRING)
      .addNullableField("d_weekend", Schema.FieldType.STRING)
      .addNullableField("d_following_holiday", Schema.FieldType.STRING)
      .addNullableField("d_first_dom", Schema.FieldType.INT32)
      .addNullableField("d_last_dom", Schema.FieldType.INT32)
      .addNullableField("d_same_day_ly", Schema.FieldType.INT32)
      .addNullableField("d_same_day_lq", Schema.FieldType.INT32)
      .addNullableField("d_current_day", Schema.FieldType.STRING)
      .addNullableField("d_current_week", Schema.FieldType.STRING)
      .addNullableField("d_current_month", Schema.FieldType.STRING)
      .addNullableField("d_current_quarter", Schema.FieldType.STRING)
      .addNullableField("d_current_year", Schema.FieldType.STRING)
      .build();

  private static Schema itemSchema =
    Schema.builder()
      .addNullableField("i_item_sk", Schema.FieldType.INT32)
      .addNullableField("i_item_id", Schema.FieldType.STRING) //                 .string,
      .addNullableField("i_rec_start_date", Schema.FieldType.DATETIME) //          .date,
      .addNullableField("i_rec_end_date", Schema.FieldType.DATETIME) //            .date,
      .addNullableField("i_item_desc", Schema.FieldType.STRING) //             .string,
      .addNullableField("i_current_price", Schema.FieldType.FLOAT) //           .decimal(7,2),
      .addNullableField(
        "i_wholesale_cost", Schema.FieldType.FLOAT) //               .decimal(7,2),
      .addNullableField("i_brand_id", Schema.FieldType.INT32) //                .int,
      .addNullableField("i_brand", Schema.FieldType.STRING) //                   .string,
      .addNullableField("i_class_id", Schema.FieldType.INT32) //                .int,
      .addNullableField("i_class", Schema.FieldType.STRING) //                   .string,
      .addNullableField("i_category_id", Schema.FieldType.INT32) //             .int,
      .addNullableField("i_category", Schema.FieldType.STRING) //       .string,
      .addNullableField("i_manufact_id", Schema.FieldType.INT32) //             .int,
      .addNullableField("i_manufact", Schema.FieldType.STRING) //                .string,
      .addNullableField("i_size", Schema.FieldType.STRING) //                    .string,
      .addNullableField("i_formulation", Schema.FieldType.STRING) //             .string,
      .addNullableField("i_color", Schema.FieldType.STRING) //                   .string,
      .addNullableField("i_units", Schema.FieldType.STRING) //                   .string,
      .addNullableField("i_container", Schema.FieldType.STRING) //               .string,
      .addNullableField("i_manager_id", Schema.FieldType.INT32) // .int,
      .addNullableField("i_product_name", Schema.FieldType.STRING) //            .string),
      .build();

  private static Schema inventorySchema =
    Schema.builder()
      .addNullableField("inv_date_sk", Schema.FieldType.INT32)
      .addNullableField("inv_item_sk", Schema.FieldType.INT32)
      .addNullableField("inv_warehouse_sk", Schema.FieldType.INT32)
      .addNullableField("inv_quantity_on_hand", Schema.FieldType.INT32)
      .build();

  private static Schema catalogSalesSchema =
    Schema.builder()
      .addNullableField("cs_sold_date_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("cs_sold_time_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("cs_ship_date_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("cs_bill_customer_sk", Schema.FieldType.INT32) //      .int,
      .addNullableField("cs_bill_cdemo_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("cs_bill_hdemo_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("cs_bill_addr_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("cs_ship_customer_sk", Schema.FieldType.INT32) //      .int,
      .addNullableField("cs_ship_cdemo_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("cs_ship_hdemo_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("cs_ship_addr_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("cs_call_center_sk", Schema.FieldType.INT32) //        .int,
      .addNullableField("cs_catalog_page_sk", Schema.FieldType.INT32) //       .int,
      .addNullableField("cs_ship_mode_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("cs_warehouse_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("cs_item_sk", Schema.FieldType.INT32) //               .int,
      .addNullableField("cs_promo_sk", Schema.FieldType.INT32) //              .int,
      .addNullableField("cs_order_number", Schema.FieldType.INT64) //          .long,
      .addNullableField("cs_quantity", Schema.FieldType.INT32) //              .int,
      .addNullableField("cs_wholesale_cost", Schema.FieldType.FLOAT) //        .decimal(7,2),
      .addNullableField("cs_list_price", Schema.FieldType.FLOAT) //            .decimal(7,2),
      .addNullableField("cs_sales_price", Schema.FieldType.FLOAT) //           .decimal(7,2),
      .addNullableField("cs_ext_discount_amt", Schema.FieldType.FLOAT) //      .decimal(7,2),
      .addNullableField("cs_ext_sales_price", Schema.FieldType.FLOAT) //       .decimal(7,2),
      .addNullableField("cs_ext_wholesale_cost", Schema.FieldType.FLOAT) //    .decimal(7,2),
      .addNullableField("cs_ext_list_price", Schema.FieldType.FLOAT) //        .decimal(7,2),
      .addNullableField("cs_ext_tax", Schema.FieldType.FLOAT) //               .decimal(7,2),
      .addNullableField("cs_coupon_amt", Schema.FieldType.FLOAT) //            .decimal(7,2),
      .addNullableField("cs_ext_ship_cost", Schema.FieldType.FLOAT) //         .decimal(7,2),
      .addNullableField("cs_net_paid", Schema.FieldType.FLOAT) //              .decimal(7,2),
      .addNullableField("cs_net_paid_inc_tax", Schema.FieldType.FLOAT) //      .decimal(7,2),
      .addNullableField("cs_net_paid_inc_ship", Schema.FieldType.FLOAT) //     .decimal(7,2),
      .addNullableField("cs_net_paid_inc_ship_tax", Schema.FieldType.FLOAT) // .decimal(7,2),
      .addNullableField("cs_net_profit", Schema.FieldType.FLOAT) //            .decimal(7,2))
      .build();


  private static Schema getCustomerDsSchema =
    Schema.builder()
      .addNullableField("c_customer_sk", Schema.FieldType.INT32) //             .int,
      .addNullableField("c_customer_id", Schema.FieldType.STRING) //             .string,
      .addNullableField("c_current_cdemo_sk", Schema.FieldType.INT32) //        .int,
      .addNullableField("c_current_hdemo_sk", Schema.FieldType.INT32) //        .int,
      .addNullableField("c_current_addr_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("c_first_shipto_date_sk", Schema.FieldType.INT32) //    .int,
      .addNullableField("c_first_sales_date_sk", Schema.FieldType.INT32) //     .int,
      .addNullableField("c_salutation", Schema.FieldType.STRING) //              .string,
      .addNullableField("c_first_name", Schema.FieldType.STRING) //              .string,
      .addNullableField("c_last_name", Schema.FieldType.STRING) //               .string,
      .addNullableField("c_preferred_cust_flag", Schema.FieldType.STRING) //     .string,
      .addNullableField("c_birth_day", Schema.FieldType.INT32) //               .int,
      .addNullableField("c_birth_month", Schema.FieldType.INT32) //             .int,
      .addNullableField("c_birth_year", Schema.FieldType.INT32) //              .int,
      .addNullableField("c_birth_country", Schema.FieldType.STRING) //           .string,
      .addNullableField("c_login", Schema.FieldType.STRING) //                   .string,
      .addNullableField("c_email_address", Schema.FieldType.STRING) //           .string,
      .addNullableField("c_last_review_date", Schema.FieldType.STRING) //        .string)
      .build();

  private static final Schema LINEITEM_SCHEMA =
    Schema.builder()
      .addInt32Field("l_orderkey")
      .addInt32Field("l_partkey")
      .addInt32Field("l_suppkey")
      .addInt32Field("l_linenumber")
      .addFloatField("l_quantity")
      .addFloatField("l_extendedprice")
      .addFloatField("l_discount")
      .addFloatField("l_tax")
      .addStringField("l_returnflag")
      .addStringField("l_linestatus")
      .addStringField("l_shipdate")
      .addStringField("l_commitdate")
      .addStringField("l_receiptdate")
      .addStringField("l_shipinstruct")
      .addStringField("l_shipmode")
      .addStringField("l_comment")
      .build();

  private static final Schema PARTSUPP_SCHEMA =
    Schema.builder()
      .addInt32Field("ps_partkey") // identifier
      .addInt32Field("ps_suppkey") // identifier
      .addInt32Field("ps_availqty") // integer
      .addFloatField("ps_supplycost") // decimal
      .addStringField("ps_comment") // variable text, size 199
      .build();

  private static final Schema REGION_SCHEMA =
    Schema.builder()
      .addInt32Field("r_regionkey") // identifier
      .addStringField("r_name") // fixed text, size 25
      .addStringField("r_comment") // variable text, size 152
      .build();

  private static final Schema SUPPLIER_SCHEMA =
    Schema.builder()
      .addInt32Field("s_suppkey") // identifier
      .addStringField("s_name") // fixed text, size 25
      .addStringField("s_address") // variable text, size 40
      .addInt32Field("s_nationkey") // identifier
      .addStringField("s_phone") // fixed text, size 15
      .addFloatField("s_acctbal") // decimal
      .addStringField("s_comment") // variable text, size 101
      .build();

  private static final Schema PART_SCHEMA =
    Schema.builder()
      .addInt32Field("p_partkey")
      .addStringField("p_name")
      .addStringField("p_mfgr")
      .addStringField("p_brand")
      .addStringField("p_type")
      .addInt32Field("p_size")
      .addStringField("p_container")
      .addFloatField("p_retailprice")
      .addStringField("p_comment")
      .build();

  private static final Schema NATION_SCHEMA =
    Schema.builder()
      .addInt32Field("n_nationkey")
      .addStringField("n_name")
      .addInt32Field("n_regionkey")
      .addStringField("n_comment")
      .build();

  private static Schema promotionSchema =
    Schema.builder()
      .addNullableField("p_promo_sk", Schema.FieldType.INT32)
      .addNullableField("p_promo_id", Schema.FieldType.STRING) //                .string,
      .addNullableField("p_start_date_sk", Schema.FieldType.INT32) // .int,
      .addNullableField("p_end_date_sk", Schema.FieldType.INT32) // .int,
      .addNullableField("p_item_sk", Schema.FieldType.INT32) // .int,
      .addNullableField("p_cost", Schema.FieldType.FLOAT) // .decimal(15,2),
      .addNullableField("p_response_target", Schema.FieldType.INT32) // .int,
      .addNullableField("p_promo_name", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_dmail", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_email", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_catalog", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_tv", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_radio", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_press", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_event", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_demo", Schema.FieldType.STRING) // .string,
      .addNullableField("p_channel_details", Schema.FieldType.STRING) // .string,
      .addNullableField("p_purpose", Schema.FieldType.STRING) // .string,
      .addNullableField("p_discount_active", Schema.FieldType.STRING) // .string),
      .build();

  private static Schema customerDemographicsSchema =
    Schema.builder()
      .addNullableField("cd_demo_sk", Schema.FieldType.INT32)
      .addNullableField("cd_gender", Schema.FieldType.STRING) //                 .string,
      .addNullableField("cd_marital_status", Schema.FieldType.STRING) //         .string,
      .addNullableField("cd_education_status", Schema.FieldType.STRING) //       .string,
      .addNullableField("cd_purchase_estimate", Schema.FieldType.INT32) //      .int,
      .addNullableField("cd_credit_rating", Schema.FieldType.STRING) //          .string,
      .addNullableField("cd_dep_count", Schema.FieldType.INT32) //              .int,
      .addNullableField("cd_dep_employed_count", Schema.FieldType.INT32) //     .int,
      .addNullableField("cd_dep_college_count", Schema.FieldType.INT32) //      .int),
      .build();

  private static Schema webSalesSchema =
    Schema.builder()
      .addNullableField("ws_sold_date_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("ws_sold_time_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("ws_ship_date_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("ws_item_sk", Schema.FieldType.INT32) //               .int,
      .addNullableField("ws_bill_customer_sk", Schema.FieldType.INT32) //      .int,
      .addNullableField("ws_bill_cdemo_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("ws_bill_hdemo_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("ws_bill_addr_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("ws_ship_customer_sk", Schema.FieldType.INT32) //      .int,
      .addNullableField("ws_ship_cdemo_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("ws_ship_hdemo_sk", Schema.FieldType.INT32) //         .int,
      .addNullableField("ws_ship_addr_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("ws_web_page_sk", Schema.FieldType.INT32) //           .int,
      .addNullableField("ws_web_site_sk", Schema.FieldType.INT32) //           .int,
      .addNullableField("ws_ship_mode_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("ws_warehouse_sk", Schema.FieldType.INT32) //          .int,
      .addNullableField("ws_promo_sk", Schema.FieldType.INT32) //              .int,
      .addNullableField("ws_order_number", Schema.FieldType.INT64) //          .long,
      .addNullableField("ws_quantity", Schema.FieldType.INT32) //              .int,
      .addNullableField("ws_wholesale_cost", Schema.FieldType.FLOAT) //        .decimal(7,2),
      .addNullableField("ws_list_price", Schema.FieldType.FLOAT) //            .decimal(7,2),
      .addNullableField("ws_sales_price", Schema.FieldType.FLOAT) //           .decimal(7,2),
      .addNullableField("ws_ext_discount_amt", Schema.FieldType.FLOAT) //      .decimal(7,2),
      .addNullableField("ws_ext_sales_price", Schema.FieldType.FLOAT) //       .decimal(7,2),
      .addNullableField("ws_ext_wholesale_cost", Schema.FieldType.FLOAT) //    .decimal(7,2),
      .addNullableField("ws_ext_list_price", Schema.FieldType.FLOAT) //        .decimal(7,2),
      .addNullableField("ws_ext_tax", Schema.FieldType.FLOAT) //               .decimal(7,2),
      .addNullableField("ws_coupon_amt", Schema.FieldType.FLOAT) //            .decimal(7,2),
      .addNullableField("ws_ext_ship_cost", Schema.FieldType.FLOAT) //         .decimal(7,2),
      .addNullableField("ws_net_paid", Schema.FieldType.FLOAT) //              .decimal(7,2),
      .addNullableField("ws_net_paid_inc_tax", Schema.FieldType.FLOAT) //      .decimal(7,2),
      .addNullableField("ws_net_paid_inc_ship", Schema.FieldType.FLOAT) //     .decimal(7,2),
      .addNullableField("ws_net_paid_inc_ship_tax", Schema.FieldType.FLOAT) // .decimal(7,2),
      .addNullableField("ws_net_profit", Schema.FieldType.FLOAT) //            .decimal(7,2)),
      .build();

  public static final String QUERY3 =
    "select  dt.d_year \n"
      + "       ,item.i_brand_id brand_id \n"
      + "       ,item.i_brand brand\n"
      + "       ,sum(ss_sales_price) sum_agg\n"
      + " from  date_dim dt \n"
      + "      ,store_sales\n"
      + "      ,item\n"
      + " where dt.d_date_sk = store_sales.ss_sold_date_sk\n"
      + "   and store_sales.ss_item_sk = item.i_item_sk\n"
      + "   and item.i_manufact_id = 816\n"
      + "   and dt.d_moy=11\n"
      + " group by dt.d_year\n"
      + "      ,item.i_brand\n"
      + "      ,item.i_brand_id\n"
      + " order by dt.d_year\n"
      + "         ,sum_agg desc\n"
      + "         ,brand_id\n"
      + " limit 100";

  public static final String QUERY7 =
    "select  i_item_id, \n"
      + "        avg(ss_quantity) agg1,\n"
      + "        avg(ss_list_price) agg2,\n"
      + "        avg(ss_coupon_amt) agg3,\n"
      + "        avg(ss_sales_price) agg4 \n"
      + " from store_sales, customer_demographics, date_dim, item, promotion\n"
      + " where ss_sold_date_sk = d_date_sk and\n"
      + "       ss_item_sk = i_item_sk and\n"
      + "       ss_cdemo_sk = cd_demo_sk and\n"
      + "       ss_promo_sk = p_promo_sk and\n"
      + "       cd_gender = 'F' and \n"
      + "       cd_marital_status = 'W' and\n"
      + "       cd_education_status = 'College' and\n"
      + "       (p_channel_email = 'N' or p_channel_event = 'N') and\n"
      + "       d_year = 2001 \n"
      + " group by i_item_id\n"
      + " order by i_item_id\n"
      + " limit 100";

  public static final String QUERY11 =
    "with year_total as (\n"
      + " select c_customer_id customer_id\n"
      + "       ,c_first_name customer_first_name\n"
      + "       ,c_last_name customer_last_name\n"
      + "       ,c_preferred_cust_flag customer_preferred_cust_flag\n"
      + "       ,c_birth_country customer_birth_country\n"
      + "       ,c_login customer_login\n"
      + "       ,c_email_address customer_email_address\n"
      + "       ,d_year dyear\n"
      + "       ,sum(ss_ext_list_price-ss_ext_discount_amt) year_total\n"
      + "       ,'s' sale_type\n"
      + " from customer\n"
      + "     ,store_sales\n"
      + "     ,date_dim\n"
      + " where c_customer_sk = ss_customer_sk\n"
      + "   and ss_sold_date_sk = d_date_sk\n"
      + " group by c_customer_id\n"
      + "         ,c_first_name\n"
      + "         ,c_last_name\n"
      + "         ,c_preferred_cust_flag \n"
      + "         ,c_birth_country\n"
      + "         ,c_login\n"
      + "         ,c_email_address\n"
      + "         ,d_year \n"
      + " union all\n"
      + " select c_customer_id customer_id\n"
      + "       ,c_first_name customer_first_name\n"
      + "       ,c_last_name customer_last_name\n"
      + "       ,c_preferred_cust_flag customer_preferred_cust_flag\n"
      + "       ,c_birth_country customer_birth_country\n"
      + "       ,c_login customer_login\n"
      + "       ,c_email_address customer_email_address\n"
      + "       ,d_year dyear\n"
      + "       ,sum(ws_ext_list_price-ws_ext_discount_amt) year_total\n"
      + "       ,'w' sale_type\n"
      + " from customer\n"
      + "     ,web_sales\n"
      + "     ,date_dim\n"
      + " where c_customer_sk = ws_bill_customer_sk\n"
      + "   and ws_sold_date_sk = d_date_sk\n"
      + " group by c_customer_id\n"
      + "         ,c_first_name\n"
      + "         ,c_last_name\n"
      + "         ,c_preferred_cust_flag \n"
      + "         ,c_birth_country\n"
      + "         ,c_login\n"
      + "         ,c_email_address\n"
      + "         ,d_year\n"
      + "         )\n"
      + "  select  \n"
      + "                  t_s_secyear.customer_id\n"
      + "                 ,t_s_secyear.customer_first_name\n"
      + "                 ,t_s_secyear.customer_last_name\n"
      + "                 ,t_s_secyear.customer_email_address\n"
      + " from year_total t_s_firstyear\n"
      + "     ,year_total t_s_secyear\n"
      + "     ,year_total t_w_firstyear\n"
      + "     ,year_total t_w_secyear\n"
      + " where t_s_secyear.customer_id = t_s_firstyear.customer_id\n"
      + "         and t_s_firstyear.customer_id = t_w_secyear.customer_id\n"
      + "         and t_s_firstyear.customer_id = t_w_firstyear.customer_id\n"
      + "         and t_s_firstyear.sale_type = 's'\n"
      + "         and t_w_firstyear.sale_type = 'w'\n"
      + "         and t_s_secyear.sale_type = 's'\n"
      + "         and t_w_secyear.sale_type = 'w'\n"
      + "         and t_s_firstyear.dyear = 1998\n"
      + "         and t_s_secyear.dyear = 1998+1\n"
      + "         and t_w_firstyear.dyear = 1998\n"
      + "         and t_w_secyear.dyear = 1998+1\n"
      + "         and t_s_firstyear.year_total > 0\n"
      + "         and t_w_firstyear.year_total > 0\n"
      + "         and case when t_w_firstyear.year_total > 0 then t_w_secyear.year_total / t_w_firstyear.year_total "
      + "else 0.0 end\n"
      + "             > case when t_s_firstyear.year_total > 0 then t_s_secyear.year_total / t_s_firstyear.year_total"
      + " else 0.0 end\n"
      + " order by t_s_secyear.customer_id\n"
      + "         ,t_s_secyear.customer_first_name\n"
      + "         ,t_s_secyear.customer_last_name\n"
      + "         ,t_s_secyear.customer_email_address\n"
      + "limit 100";

  public static final String QUERY22 =
    "select  i_product_name\n"
      + "             ,i_brand\n"
      + "             ,i_class\n"
      + "             ,i_category\n"
      + "             ,avg(inv_quantity_on_hand) qoh\n"
      + "       from inventory\n"
      + "           ,date_dim\n"
      + "           ,item\n"
      + "       where inv_date_sk=d_date_sk\n"
      + "              and inv_item_sk=i_item_sk\n"
      + "              and d_month_seq between 1200 and 1200 + 11\n"
      + "       group by rollup(i_product_name\n"
      + "                       ,i_brand\n"
      + "                       ,i_class\n"
      + "                       ,i_category)\n"
      + "order by qoh, i_product_name, i_brand, i_class, i_category\n"
      + "limit 100";

  public static final String QUERY38 =
    "select  count(*) from (\n"
      + "    select distinct c_last_name, c_first_name, d_date\n"
      + "    from store_sales, date_dim, customer\n"
      + "          where store_sales.ss_sold_date_sk = date_dim.d_date_sk\n"
      + "      and store_sales.ss_customer_sk = customer.c_customer_sk\n"
      + "      and d_month_seq between 1189 and 1189 + 11\n"
      + "  intersect\n"
      + "    select distinct c_last_name, c_first_name, d_date\n"
      + "    from catalog_sales, date_dim, customer\n"
      + "          where catalog_sales.cs_sold_date_sk = date_dim.d_date_sk\n"
      + "      and catalog_sales.cs_bill_customer_sk = customer.c_customer_sk\n"
      + "      and d_month_seq between 1189 and 1189 + 11\n"
      + "  intersect\n"
      + "    select distinct c_last_name, c_first_name, d_date\n"
      + "    from web_sales, date_dim, customer\n"
      + "          where web_sales.ws_sold_date_sk = date_dim.d_date_sk\n"
      + "      and web_sales.ws_bill_customer_sk = customer.c_customer_sk\n"
      + "      and d_month_seq between 1189 and 1189 + 11\n"
      + ") hot_cust\n"
      + "limit 100";

  public static final String QUERY42 =
    "select  dt.d_year\n"
      + "    ,item.i_category_id\n"
      + "    ,item.i_category\n"
      + "    ,sum(ss_ext_sales_price)\n"
      + " from   date_dim dt\n"
      + "    ,store_sales\n"
      + "    ,item\n"
      + " where dt.d_date_sk = store_sales.ss_sold_date_sk\n"
      + "    and store_sales.ss_item_sk = item.i_item_sk\n"
      + "    and item.i_manager_id = 1   \n"
      + "    and dt.d_moy=11\n"
      + "    and dt.d_year=1998\n"
      + " group by   dt.d_year\n"
      + "        ,item.i_category_id\n"
      + "        ,item.i_category\n"
      + " order by       sum(ss_ext_sales_price) desc,dt.d_year\n"
      + "        ,item.i_category_id\n"
      + "        ,item.i_category\n"
      + "limit 100";
}
