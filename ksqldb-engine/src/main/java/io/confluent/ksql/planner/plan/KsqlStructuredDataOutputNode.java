/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.planner.plan;

import static io.confluent.ksql.metastore.model.DataSource.DataSourceType;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.execution.timestamp.TimestampColumn;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.query.id.QueryIdGenerator;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.structured.SchemaKStream;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class KsqlStructuredDataOutputNode extends OutputNode {

  private final KsqlTopic ksqlTopic;
  private final boolean doCreateInto;
  private final ImmutableSet<SerdeOption> serdeOptions;
  private final SourceName intoSourceName;

  public KsqlStructuredDataOutputNode(
      final PlanNodeId id,
      final PlanNode source,
      final LogicalSchema schema,
      final Optional<TimestampColumn> timestampColumn,
      final KsqlTopic ksqlTopic,
      final OptionalInt limit,
      final boolean doCreateInto,
      final Set<SerdeOption> serdeOptions,
      final SourceName intoSourceName
  ) {
    super(id, source, schema, limit, timestampColumn);

    this.serdeOptions = ImmutableSet.copyOf(requireNonNull(serdeOptions, "serdeOptions"));
    this.ksqlTopic = requireNonNull(ksqlTopic, "ksqlTopic");
    this.doCreateInto = doCreateInto;
    this.intoSourceName = requireNonNull(intoSourceName, "intoSourceName");

    validate(source, intoSourceName);
  }

  public boolean isDoCreateInto() {
    return doCreateInto;
  }

  public KsqlTopic getKsqlTopic() {
    return ksqlTopic;
  }

  public Set<SerdeOption> getSerdeOptions() {
    return serdeOptions;
  }

  public SourceName getIntoSourceName() {
    return intoSourceName;
  }

  @Override
  public QueryId getQueryId(final QueryIdGenerator queryIdGenerator) {
    final String base = queryIdGenerator.getNext().toUpperCase();
    if (!doCreateInto) {
      return new QueryId("INSERTQUERY_" + base);
    }
    if (getNodeOutputType().equals(DataSourceType.KTABLE)) {
      return new QueryId("CTAS_" + getId().toString().toUpperCase() + "_" + base);
    }
    return new QueryId("CSAS_" + getId().toString().toUpperCase() + "_" + base);
  }

  @Override
  public SchemaKStream<?> buildStream(final KsqlQueryBuilder builder) {
    final PlanNode source = getSource();
    final SchemaKStream<?> schemaKStream = source.buildStream(builder);

    final QueryContext.Stacker contextStacker = builder.buildNodeContext(getId().toString());

    return schemaKStream.into(
        getKsqlTopic().getKafkaTopicName(),
        getKsqlTopic().getValueFormat(),
        serdeOptions,
        contextStacker,
        getTimestampColumn()
    );
  }

  private static void validate(
      final PlanNode source,
      final SourceName intoSourceName
  ) {
    if (!(source instanceof VerifiableNode)) {
      throw new IllegalArgumentException("VerifiableNode required");
    }

    ((VerifiableNode) source)
        .validateKeyPresent(intoSourceName);

    final LogicalSchema schema = source.getSchema();

    final String duplicates = schema.columns().stream()
        .map(Column::name)
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
        .entrySet()
        .stream()
        .filter(e -> e.getValue() > 1)
        .map(Entry::getKey)
        .map(ColumnName::toString)
        .collect(Collectors.joining(", "));

    if (!duplicates.isEmpty()) {
      throw new IllegalArgumentException("Value columns clash with key columns: " + duplicates);
    }
  }
}
