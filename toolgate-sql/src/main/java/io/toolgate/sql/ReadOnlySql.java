package io.toolgate.sql;

import io.toolgate.api.ArgValue;
import io.toolgate.api.TextValue;
import io.toolgate.spi.ArgumentValidator;
import java.util.Optional;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * One query and nothing else. The text checks run first and do most of the work; the statement
 * tree then confirms that what remains is a single {@code SELECT}. Either one denying is enough.
 */
final class ReadOnlySql implements ArgumentValidator {

    @Override
    public String kind() {
        return "readOnlySql";
    }

    @Override
    public String fingerprint() {
        return "";
    }

    @Override
    public Optional<String> problem(ArgValue value) {
        if (!(value instanceof TextValue(String sql))) {
            return Optional.of("must be a string");
        }
        return SqlText.problem(sql).or(() -> statementProblem(sql)).map(problem -> "SQL " + problem);
    }

    private static Optional<String> statementProblem(String sql) {
        Statements statements;
        try {
            // Parsed on the caller's thread: CCJSqlParserUtil would start a thread per parse for its timeout,
            // and the text checks already bound the input.
            statements = new CCJSqlParser(sql).Statements();
        } catch (ParseException | RuntimeException e) {
            return Optional.of("cannot be read as a query");
        } catch (StackOverflowError e) {
            // Nesting without parentheses, such as CASE inside CASE, is not bounded by the text checks.
            // The parser holds no state beyond this call, so the overflow ends here.
            return Optional.of("nests too deeply to read");
        }
        if (statements.size() != 1) {
            return Optional.of("must be exactly one statement");
        }
        Statement statement = statements.getFirst();
        return statement instanceof Select select ? selectProblem(select) : Optional.of("is not a query");
    }

    private static Optional<String> selectProblem(Select select) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item.getSelect() == null) {
                    return Optional.of("has a WITH part that is not a query");
                }
                Optional<String> problem = selectProblem(item.getSelect());
                if (problem.isPresent()) {
                    return problem;
                }
            }
        }
        if (select.getForMode() != null || select.getForUpdate() != null) {
            return Optional.of("locks rows");
        }
        return switch (select) {
            case PlainSelect plain -> plain.getIntoTables() != null || plain.getIntoTempTable() != null
                    || plain.getMySqlSelectIntoClause() != null
                    ? Optional.of("writes its result with INTO")
                    : Optional.empty();
            case SetOperationList operations -> operations.getSelects().stream()
                    .map(ReadOnlySql::selectProblem)
                    .flatMap(Optional::stream)
                    .findFirst();
            case ParenthesedSelect parenthesed -> selectProblem(parenthesed.getSelect());
            default -> Optional.of("is a " + select.getClass().getSimpleName() + ", not a plain query");
        };
    }
}
