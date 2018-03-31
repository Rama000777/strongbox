package org.carlspring.strongbox.data.criteria;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.EntityManager;

import org.carlspring.strongbox.data.domain.GenericEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery;
import com.orientechnologies.orient.object.db.OObjectDatabaseTx;

/**
 * {@link QueryTemplate} implementation for OrientDB engine.
 * 
 * @author sbespalov
 *
 */
public class OQueryTemplate<R, T extends GenericEntity> implements QueryTemplate<R, T>
{
    private static final Logger logger = LoggerFactory.getLogger(OQueryTemplate.class);

    protected EntityManager entityManager;

    public OQueryTemplate()
    {
        super();
    }

    public OQueryTemplate(EntityManager entityManager)
    {
        super();
        this.entityManager = entityManager;
    }

    public EntityManager getEntityManager()
    {
        return entityManager;
    }

    public R select(Selector<T> s)
    {
        String sQuery = calculateQueryString(s);

        OSQLSynchQuery<T> oQuery = new OSQLSynchQuery<>(sQuery);
        Map<String, Object> parameterMap = exposeParameterMap(s.getPredicate());

        logger.debug(String.format("Executing SQL query:%n\t[%s]%nWith parameters:%n\t[%s]", sQuery, parameterMap));

        Object result = getEmDelegate().command(oQuery)
                                       .execute(parameterMap);
        if (result instanceof Collection && !((Collection) result).isEmpty()
                && ((Collection) result).iterator().next() instanceof ODocument)
        {
            // Commonly we don't need ODocument results, so if it's a ODocument
            // then probably we assume to get it's contents
            return (R) ((Collection<ODocument>) result).iterator().next().fieldValues()[0];
        }
        else
        {
            return (R) result;
        }
    }

    public OObjectDatabaseTx getEmDelegate()
    {
        return (OObjectDatabaseTx) entityManager.getDelegate();
    }

    public Map<String, Object> exposeParameterMap(Predicate p)
    {
        return exposeParameterMap(p, 0);
    }

    private Map<String, Object> exposeParameterMap(Predicate p,
                                                   int tokenCount)
    {
        HashMap<String, Object> result = new HashMap<>();
        Expression e = p.getExpression();
        if (e != null)
        {
            result.put(calculateParameterName(e.getProperty(), tokenCount), e.getValue());
        }

        for (Predicate predicate : p.getChildPredicateList())
        {
            result.putAll(exposeParameterMap(predicate, tokenCount++));
        }

        return result;
    }

    public String calculateQueryString(Selector<T> selector)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(selector.getProjection());
        sb.append(" FROM ").append(selector.getTargetClass().getSimpleName());

        Predicate p = selector.getPredicate();
        if (p.isEmpty())
        {
            return sb.toString();
        }

        sb.append(" WHERE ");
        sb.append(predicateToken(p, 0));

        Paginator paginator = selector.getPaginator();
        if (paginator != null && paginator.getProperty() != null && !paginator.getProperty().trim().isEmpty())
        {
            sb.append(String.format(" ORDER BY %s %s", paginator.getProperty(), paginator.getOrder()));
        }

        if (paginator != null && paginator.getSkip() > 0)
        {
            sb.append(String.format(" SKIP %s", paginator.getSkip()));
        }
        if (paginator != null && paginator.getLimit() > 0)
        {
            sb.append(String.format(" LIMIT %s", paginator.getLimit()));
        }

        if (selector.isFetch())
        {
            sb.append(" FETCHPLAN *:-1");
        }

        return sb.toString();
    }

    protected String predicateToken(Predicate p,
                                    int tokenCount)
    {
        if (p.isEmpty())
        {
            return "";
        }

        StringBuffer sb = new StringBuffer();
        if (p.getExpression() != null)
        {
            sb.append(expressionToken(p.getExpression(), tokenCount));
        }

        for (Predicate predicate : p.getChildPredicateList())
        {
            if (sb.length() > 0)
            {
                sb.append(String.format(" %s ", p.getOperator().name()));
            }
            sb.append(predicateToken(predicate, tokenCount++));
        }

        if (p.isNesteed())
        {
            sb.insert(0, "(").append(")");
        }

        if (p.isNegated())
        {
            sb.insert(0, " NOT ");
        }

        return sb.toString();
    }

    protected String expressionToken(Expression e,
                                     int n)
    {
        String experssionLeft = expressionLeftToken(e);
        String operator = expressionOperatorToken(e);
        String expressionRight = expressionRightToken(e, n);

        return new StringBuffer().append(experssionLeft)
                                 .append(operator)
                                 .append(expressionRight)
                                 .toString();
    }

    protected String expressionLeftToken(Expression e)
    {
        switch (e.getOperator())
        {
        case CONTAINS:
            String property = e.getProperty();
            return property.substring(0, property.indexOf("."));
        default:
            break;
        }
        return e.getProperty();
    }

    protected String expressionRightToken(Expression e,
                                          int n)
    {
        switch (e.getOperator())
        {
        case CONTAINS:
            String property = e.getProperty();
            property = property.substring(property.indexOf(".") + 1);

            return String.format("(%s = :%s)", property, calculateParameterName(property, n));
        default:
            break;
        }
        String property = e.getProperty();
        return String.format(":%s", calculateParameterName(property, n));
    }

    private String calculateParameterName(String property,
                                          int n)
    {
        property = property.replace(".toLowerCase()", "");
        return String.format("%s_%s", property.substring(property.lastIndexOf(".") + 1), n);
    }

    protected String expressionOperatorToken(Expression e)
    {
        switch (e.getOperator())
        {
        case EQ:
            return " = ";
        case LE:
            return " <= ";
        case GE:
            return " >=";            
        case LIKE:
            return " LIKE ";
        case CONTAINS:
            return " CONTAINS ";
        }
        return null;
    }
}
