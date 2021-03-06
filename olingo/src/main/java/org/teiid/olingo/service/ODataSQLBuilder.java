/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.olingo.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLXML;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.edm.EdmOperation;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.commons.core.edm.primitivetype.EdmInt32;
import org.apache.olingo.commons.core.edm.primitivetype.SingletonPrimitiveType;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.uri.*;
import org.apache.olingo.server.api.uri.queryoption.*;
import org.apache.olingo.server.api.uri.queryoption.OrderByItem;
import org.apache.olingo.server.core.RequestURLHierarchyVisitor;
import org.apache.olingo.server.core.uri.UriResourceEntitySetImpl;
import org.apache.olingo.server.core.uri.parser.Parser;
import org.apache.olingo.server.core.uri.parser.UriParserException;
import org.apache.olingo.server.core.uri.validator.UriValidationException;
import org.teiid.core.TeiidException;
import org.teiid.core.TeiidProcessingException;
import org.teiid.core.types.BlobImpl;
import org.teiid.core.types.BlobType;
import org.teiid.core.types.ClobImpl;
import org.teiid.core.types.ClobType;
import org.teiid.core.types.DataTypeManager;
import org.teiid.core.types.InputStreamFactory;
import org.teiid.core.types.JDBCSQLTypeInfo;
import org.teiid.core.types.SQLXMLImpl;
import org.teiid.metadata.Column;
import org.teiid.metadata.KeyRecord;
import org.teiid.metadata.MetadataStore;
import org.teiid.metadata.Schema;
import org.teiid.metadata.Table;
import org.teiid.odata.api.SQLParameter;
import org.teiid.olingo.ODataPlugin;
import org.teiid.olingo.common.ODataTypeManager;
import org.teiid.olingo.service.ProcedureSQLBuilder.ProcedureReturn;
import org.teiid.olingo.service.TeiidServiceHandler.OperationParameterValueProvider;
import org.teiid.olingo.service.TeiidServiceHandler.UniqueNameGenerator;
import org.teiid.query.sql.lang.*;
import org.teiid.query.sql.symbol.AggregateSymbol;
import org.teiid.query.sql.symbol.AliasSymbol;
import org.teiid.query.sql.symbol.Constant;
import org.teiid.query.sql.symbol.ElementSymbol;
import org.teiid.query.sql.symbol.Expression;
import org.teiid.query.sql.symbol.GroupSymbol;
import org.teiid.query.sql.symbol.Reference;

public class ODataSQLBuilder extends RequestURLHierarchyVisitor {
    private final MetadataStore metadata;
    private boolean prepared = true;
    private final ArrayList<SQLParameter> params = new ArrayList<SQLParameter>();
    private final ArrayList<TeiidException> exceptions = new ArrayList<TeiidException>();
    private DocumentNode context;
    private SkipOption skipOption;
    private TopOption topOption;
    private boolean countOption;
    private OrderBy orderBy;
    private boolean selectionComplete;
    private String nextToken;
    private boolean aliasedGroups;
    private boolean countQuery = false;
    private boolean reference = false;
    private String baseURI;
    private ServiceMetadata serviceMetadata;
    private UniqueNameGenerator nameGenerator;
    private ExpandOption expandOption;
    private URLParseService parseService;
    private OData odata;
    private boolean navigation = false;
    private OperationParameterValueProvider parameters;
        
    class URLParseService {
        public Query parse(String rawPath) throws TeiidException {
            try {
                rawPath = rawPath.replace("$root", "");
                UriInfo uriInfo = new Parser(serviceMetadata.getEdm(), odata).parseUri(rawPath, null, null);                
                ODataSQLBuilder visitor = new ODataSQLBuilder(odata, metadata,
                        prepared, aliasedGroups, baseURI, serviceMetadata,
                        nameGenerator) {
                    public void visit(OrderByOption option) {
                        //no implicit ordering now.
                    }
                };
                visitor.visit(uriInfo);
                return visitor.selectQuery();
            } catch (UriParserException e) {
                throw new TeiidException(e);
            } catch (UriValidationException e) {
                throw new TeiidException(e);
            }
        }    
    }    
    
    public ODataSQLBuilder(OData odata, MetadataStore metadata, boolean prepared,
            boolean aliasedGroups, String baseURI,
            ServiceMetadata serviceMetadata, UniqueNameGenerator nameGenerator) {
        this.odata = odata;
        this.metadata = metadata;
        this.prepared = prepared;
        this.aliasedGroups = aliasedGroups;
        this.baseURI = baseURI;
        this.serviceMetadata = serviceMetadata;
        this.nameGenerator = nameGenerator;
        this.parseService = new URLParseService();
    }

    public DocumentNode getContext() {
        return this.context;
    }

    public boolean includeTotalSize() {
        return countOption;
    }

    public Integer getSkip() {
        if (skipOption == null) {
            return null;
        }
        return skipOption.getValue();
    }

    public Integer getTop() {
        if (topOption == null) {
            return null;
        }
        return topOption.getValue();
    }
    
    public boolean hasNavigation() {
        return this.navigation;
    }

    public Query selectQuery() throws TeiidException {
        
        if (this.expandOption != null) {
            processExpandOption(this.expandOption);
        }
        
        if (!this.exceptions.isEmpty()) {
            throw this.exceptions.get(0);
        }

        Query query = this.context.buildQuery();
        if (this.countQuery) {
            AggregateSymbol aggregateSymbol = new AggregateSymbol(AggregateSymbol.Type.COUNT.name(), false, null);
            Select select = new Select(Arrays.asList(aggregateSymbol));
            query.setSelect(select);
        }

        if (this.orderBy != null & !countQuery) {
            query.setOrderBy(this.orderBy);
        }
        return query;
    }
    
    private void processExpandOption(ExpandOption option) {
        if (option.getExpandItems().size() > 1) {
            this.exceptions.add(new TeiidNotImplementedException(ODataPlugin.Util.gs(ODataPlugin.Event.TEIID16042)));
            return;
        }
        for (ExpandItem ei:option.getExpandItems()) {
            
            try {
                ExpandSQLBuilder esb = new ExpandSQLBuilder(ei);
                EdmNavigationProperty property = esb.getNavigationProperty();
                ExpandDocumentNode expandResource = ExpandDocumentNode.buildExpand(
                        property, this.metadata, this.odata, this.nameGenerator, true,
                        getUriInfo(), this.parseService);
                
                this.context.joinTable(expandResource, property.isCollection(), JoinType.JOIN_LEFT_OUTER);
                
                // process $filter
                if (ei.getFilterOption() != null) {
                    Expression expandCriteria = processFilterOption(ei.getFilterOption(), expandResource);
                    expandResource.addCriteria(expandCriteria);
                    
                }
                
                this.context.addCriteria(expandResource.getCriteria());
                this.context.setFromClause(expandResource.getFromClause());
                
                if (ei.getOrderByOption() != null) {
                    if (this.orderBy == null) {
                        this.orderBy = new OrderBy();
                    }
                    processOrderBy(this.orderBy, ei.getOrderByOption().getOrders(), expandResource);
                }
                
                // process $select
                processSelectOption(ei.getSelectOption(), expandResource, this.reference);
                this.context.addExpand(expandResource);
                
                if (ei.getSkipOption() != null) {
                    expandResource.setSkip(ei.getSkipOption().getValue());
                }
                
                if (ei.getCountOption() != null) {
                    expandResource.setCalculateCount(ei.getCountOption().getValue());
                }
                
                if (ei.getTopOption() != null) {
                    expandResource.setTop(ei.getTopOption().getValue());
                }

                if (ei.getExpandOption() != null
                        || ei.getSearchOption() != null
                        || ei.getLevelsOption() != null) {
                    this.exceptions.add(new TeiidNotImplementedException(
                            ODataPlugin.Event.TEIID16041, ODataPlugin.Util.gs(ODataPlugin.Event.TEIID16041)));
                }                            
            } catch (TeiidException e) {
                this.exceptions.add(e);
            }            
        }
    }

    private Expression processFilterOption(FilterOption option, DocumentNode resource) {
        ODataExpressionToSQLVisitor visitor = new ODataExpressionToSQLVisitor(
                resource, this.prepared, getUriInfo(), this.metadata, this.odata, 
                this.nameGenerator, this.params, this.parseService);
        Expression filter = null;
        try {
            filter = visitor.getExpression(option.getExpression());
        } catch (TeiidException e) {
            this.exceptions.add(e);
        }
        return filter;
    }

    public List<SQLParameter> getParameters(){
        return this.params;
    }

    @Override
    public void visit(UriResourceEntitySet info) {
        try {
            this.context = DocumentNode.build(info.getEntitySet().getEntityType(), 
                    info.getKeyPredicates(), this.metadata,
                    this.odata, this.nameGenerator, this.aliasedGroups,
                    getUriInfo(), this.parseService);
        } catch (TeiidException e) {
            this.exceptions.add(e);
        }
    }

    @Override
    public void visit(SkipOption option) {
        this.skipOption = option;
    }

    @Override
    public void visit(TopOption option) {
        this.topOption = option;
    }

    @Override
    public void visit(CountOption info) {
        this.countOption = info.getValue();
    }

    @Override
    public void visit(SelectOption option) {
        if (this.selectionComplete) {
            return;
        }
        processSelectOption(option, this.context, this.reference);
    }

    private void processSelectOption(SelectOption option, DocumentNode resource, boolean onlyReference) {
        if (option == null) {
            // default select columns
            resource.addAllColumns(onlyReference);
        }
        else {
            boolean addkeys = true;
            ArrayList<String> keys = new ArrayList<String>(resource.getKeyColumnNames());
            for (SelectItem si:option.getSelectItems()) {
                if (si.isStar()) {
                    resource.addAllColumns(onlyReference);
                    addkeys = false;
                    continue;
                }

                try {
                    ODataExpressionToSQLVisitor visitor = new ODataExpressionToSQLVisitor(
                            resource, false,
                            getUriInfo(), this.metadata, this.odata, this.nameGenerator, this.params,
                            this.parseService);
                    ElementSymbol expr = (ElementSymbol)visitor.getExpression(si.getResourcePath());
                    resource.addVisibleColumn(expr.getShortName(), expr);
                    keys.remove(expr.getShortName());
                } catch (TeiidException e) {
                    this.exceptions.add(e);
                }
            }
            if (!keys.isEmpty() && addkeys) {
                for (String key:keys) {
                    ElementSymbol es = new ElementSymbol(key, resource.getGroupSymbol());
                    if (!resource.hasProjectedColumn(es)) {
                        resource.addProjectedColumn(key, es, false);
                    }
                }
            }
        }
    }

    @Override
    public void visit(OrderByOption option) {        
        if (option == null || option.getOrders().isEmpty()) {
            this.orderBy = this.context.addDefaultOrderBy();
        }
        else {
            List<OrderByItem> orderBys = option.getOrders();
            this.orderBy = processOrderBy(new OrderBy(), orderBys, this.context);
        }
    }

    private OrderBy processOrderBy(OrderBy orderBy, List<OrderByItem> orderByItems, DocumentNode resource) {
        for (OrderByItem obitem:orderByItems) {
            ODataExpressionToSQLVisitor visitor = new ODataExpressionToSQLVisitor(
                    resource, false,
                    getUriInfo(), this.metadata, this.odata, this.nameGenerator, this.params,
                    this.parseService);
            try {
                Expression expr = visitor.getExpression(obitem.getExpression());
                if (expr instanceof ElementSymbol) {
                    orderBy.addVariable(expr, !obitem.isDescending());
                    visitor.getExpresionEntityResource().addProjectedColumn(((ElementSymbol)expr).getShortName(), expr, false);
                }
                else {
                    AliasSymbol alias = new AliasSymbol("_orderByAlias", expr);
                    orderBy.addVariable(alias, !obitem.isDescending());
                    visitor.getExpresionEntityResource().addProjectedColumn(alias, false, EdmInt32.getInstance(), false);
                }
            } catch (TeiidException e) {
                this.exceptions.add(e);
            }
        }
        return orderBy;
    }

    @Override
    public void visit(FilterOption info) {
        ODataExpressionToSQLVisitor visitor = new ODataExpressionToSQLVisitor(
                this.context, this.prepared, getUriInfo(), this.metadata, this.odata,
                this.nameGenerator, this.params, this.parseService);
        
        Expression filter = null;
        try {
        	filter = visitor.getExpression(info.getExpression());
        } catch (TeiidException e) {
            this.exceptions.add(e);
        }
        
        // Here Lambda operation may have joined a table and changed the context.
        this.context = visitor.getEntityResource();
        this.context.addCriteria(filter);
    }
    
    @Override
    public void visit(UriResourceNavigation info) {
        EdmNavigationProperty property = info.getProperty();
        try {
            DocumentNode joinResource = DocumentNode.build(property.getType(),
                    info.getKeyPredicates(), this.metadata, this.odata, this.nameGenerator,
                    true, getUriInfo(), parseService);

            this.context.joinTable(joinResource, property.isCollection(), JoinType.JOIN_INNER);
            // In the context of canonical queries if key predicates are available then do not set the criteria 
            if (joinResource.getCriteria() == null) {
                joinResource.addCriteria(this.context.getCriteria());
            }
            this.context = joinResource;
            this.navigation = true;
        } catch (TeiidException e) {
            this.exceptions.add(e);
        }
    }

    @Override
    public void visit(UriResourcePrimitiveProperty info) {
        String propertyName = info.getProperty().getName();
        ElementSymbol es = new ElementSymbol(propertyName, this.context.getGroupSymbol());
        this.context.addVisibleColumn(propertyName, es);
        this.selectionComplete = true;
    }
    
    public String getNextToken() {
        return nextToken;
    }
    
    @Override
    public void visit(SkipTokenOption option) {
        if (option != null) {
            this.nextToken = option.getValue();
        }
    }
    
    @Override
    public void visit(SearchOption option) {
        this.exceptions.add(new TeiidNotImplementedException(
                ODataPlugin.Event.TEIID16035, ODataPlugin.Util.gs(ODataPlugin.Event.TEIID16035)));
    }
    
    public Insert insert(EdmEntityType entityType, Entity entity,
            List<UriParameter> keys, boolean prepared) throws TeiidException {
        Table entityTable = findTable(entityType.getName(), this.metadata);
        DocumentNode resource = new DocumentNode(entityTable, new GroupSymbol(entityTable.getFullName()), entityType);
        
        List<Reference> referenceValues = new ArrayList<Reference>();
        List<Constant> constantValues = new ArrayList<Constant>();
        Insert insert = new Insert();
        insert.setGroup(resource.getGroupSymbol());
        if (keys != null) {
	        for (UriParameter key : keys) {
	            EdmProperty edmProperty = (EdmProperty)entityType.getProperty(key.getName());
	            Column column = entityTable.getColumnByName(edmProperty.getName());
	            Object propertyValue = ODataTypeManager.parseLiteral(edmProperty, column.getJavaType(), key.getText());
	            Property existing = entity.getProperty(edmProperty.getName());
	            if (existing == null || 
	                    (existing.getValue() == null && propertyValue != null) || 
	                    (existing.getValue() != null && propertyValue == null) || 
	                    (existing.getValue() != null && !existing.getValue().equals(propertyValue))) {
	            	throw new TeiidProcessingException(ODataPlugin.Util.gs(ODataPlugin.Event.TEIID16048, 
	            	        edmProperty.getName()));
	            }
	        }
        }
        int i = 0;
        for (Property prop : entity.getProperties()) {
            EdmProperty edmProp = (EdmProperty)entityType.getProperty(prop.getName());
            Column column = entityTable.getColumnByName(edmProp.getName());
            insert.addVariable(new ElementSymbol(column.getName(), resource.getGroupSymbol()));
            
            if (prepared) {
                referenceValues.add(new Reference(i++));
                this.params.add(asParam(edmProp, prop.getValue()));
            }
            else {
                constantValues.add(new Constant(asParam(edmProp, prop.getValue()).getValue()));
            }
        }
        
        if (prepared) {
            insert.setValues(referenceValues);
        }
        else {
            insert.setValues(constantValues);
        }
        return insert;
    }
    static SQLParameter asParam(EdmProperty edmProp, Object value) throws TeiidException {
        return asParam(edmProp, value, false);
    }    
    
    static SQLParameter asParam(EdmProperty edmProp, Object value, boolean rawValue) throws TeiidException {
        String teiidType = ODataTypeManager.teiidType((SingletonPrimitiveType)edmProp.getType(), edmProp.isCollection());
        int sqlType = JDBCSQLTypeInfo.getSQLType(teiidType);
        if (value == null) {
            return new SQLParameter(null, sqlType);
        }
        
        if (rawValue) {
            return new SQLParameter(ODataTypeManager.convertByteArrayToTeiidRuntimeType(
                    DataTypeManager.getDataTypeClass(teiidType), (byte[])value, 
                    ((SingletonPrimitiveType)edmProp.getType()).getFullQualifiedName().getFullQualifiedNameAsString()), 
                    sqlType);            
        }        
        return new SQLParameter(ODataTypeManager.convertToTeiidRuntimeType(
                DataTypeManager.getDataTypeClass(teiidType), value, 
                ((SingletonPrimitiveType)edmProp.getType()).getFullQualifiedName().getFullQualifiedNameAsString()), 
                sqlType);
    }    
    
    private Table findTable(String tableName, MetadataStore store) {
        int idx = tableName.indexOf('.');
        if (idx > 0) {
            Schema s = store.getSchema(tableName.substring(0, idx));
            if (s != null) {
                Table t = s.getTable(tableName.substring(idx+1));
                if (t != null) {
                    return t;
                }
            }
        }
        for (Schema s : store.getSchemaList()) {
            Table t = s.getTables().get(tableName);
            if (t != null) {
                return t;
            }
        }        
        return null;
    }
    //TODO: allow the generated key building.
    public Query selectWithEntityKey(EdmEntityType entityType, Entity entity,
            Map<String, Object> generatedKeys, Set<EdmNavigationProperty> expand) throws TeiidException {
        Table table = findTable(entityType.getName(), this.metadata);

        DocumentNode resource = new DocumentNode(table, new GroupSymbol(table.getFullName()), entityType);
        resource.setFromClause(new UnaryFromClause(new GroupSymbol(table.getFullName())));
        resource.addAllColumns(false);
        this.context = resource;
        
        FromClause from = resource.getFromClause();
        Criteria criteria = null;
        
        for (EdmNavigationProperty navProperty: expand) {
            DocumentNode joinResource = ExpandDocumentNode.buildExpand(
                    navProperty, this.metadata, this.odata, this.nameGenerator,
                    this.aliasedGroups, getUriInfo(), this.parseService);
                    
            resource.joinTable(joinResource, navProperty.isCollection(), JoinType.JOIN_INNER);
            // In the context of canonical queries if key predicates are available then do not set the criteria 
            if (joinResource.getCriteria() == null) {
                joinResource.addCriteria(resource.getCriteria());
            }
            joinResource.addAllColumns(false);
            resource = joinResource;
            this.context.addExpand(joinResource);
            from = resource.getFromClause();
            criteria = resource.getCriteria();
        }
        
        this.context.setFromClause(from);
        Query query = this.context.buildQuery();

        KeyRecord pk = table.getPrimaryKey();
        for (Column c:pk.getColumns()) {
            Property prop = entity.getProperty(c.getName());
            Constant right = null;
            if (prop != null) {
                right = new Constant(ODataTypeManager.convertToTeiidRuntimeType(c.getJavaType(), prop.getValue(), null));
            } else {
                Object value = generatedKeys.get(c.getName());
                if (value == null) {
                    throw new TeiidProcessingException(ODataPlugin.Util.gs(ODataPlugin.Event.TEIID16016, entityType.getName()));
                }
                right = new Constant(value);
            }
            ElementSymbol left = new ElementSymbol(c.getName(), this.context.getGroupSymbol());
            if (criteria == null) {
                criteria = new CompareCriteria(left,AbstractCompareCriteria.EQ, right);
            }
            else {
                CompareCriteria rightCC = new CompareCriteria(left,AbstractCompareCriteria.EQ, right);
                criteria = new CompoundCriteria(CompoundCriteria.AND, criteria, rightCC);
            }
        }
        query.setCriteria(criteria);
        return query;
    }
    
    public Update update(EdmEntityType entityType, Entity entity, boolean prepared) throws TeiidException {
        Update update = new Update();
        update.setGroup(this.context.getGroupSymbol());
        
        int i = 0;
        for (Property property : entity.getProperties()) {
            EdmProperty edmProperty = (EdmProperty)entityType.getProperty(property.getName());
            Column column = this.context.getColumnByName(edmProperty.getName());
            ElementSymbol symbol = new ElementSymbol(column.getName(), this.context.getGroupSymbol());
            boolean add = true;
            for (String c : this.context.getKeyColumnNames()) {
                if (c.equals(column.getName())) {
                    add = false;
                    break;
                }
            }
            if (add) {
                if (prepared) {
                    update.addChange(symbol, new Reference(i++));
                    this.params.add(asParam(edmProperty, property.getValue()));
                }
                else {
                    update.addChange(symbol, new Constant(asParam(edmProperty, property.getValue()).getValue()));
                }
            }
        }
        update.setCriteria(this.context.getCriteria());
        return update;
    }
    
    public Update updateProperty(EdmProperty edmProperty, Property property,
            boolean prepared, boolean rawValue) throws TeiidException {
        Update update = new Update();
        update.setGroup(this.context.getGroupSymbol());

        Column column = this.context.getColumnByName(edmProperty.getName());
        ElementSymbol symbol = new ElementSymbol(column.getName(), this.context.getGroupSymbol());
        
        if (prepared) {
            update.addChange(symbol, new Reference(0));
            this.params.add(asParam(edmProperty, property.getValue(), rawValue));
        }
        else {
            update.addChange(symbol, new Constant(asParam(edmProperty, property.getValue()).getValue()));
        }
        
        update.setCriteria(this.context.getCriteria());
        return update;
    }
    
    public Update updateStreamProperty(EdmProperty edmProperty, final InputStream content) {
        Update update = new Update();
        update.setGroup(this.context.getGroupSymbol());

        Column column = this.context.getColumnByName(edmProperty.getName());
        ElementSymbol symbol = new ElementSymbol(column.getName(), this.context.getGroupSymbol());
        
        update.addChange(symbol, new Reference(0));
        Class<?> lobType = DataTypeManager.getDataTypeClass(column.getDatatype().getRuntimeTypeName());
        int sqlType = JDBCSQLTypeInfo.getSQLType(column.getDatatype().getRuntimeTypeName());
        if (content == null) {
            this.params.add(new SQLParameter(null, sqlType));
        } else {
            Object value = null;
            InputStreamFactory isf = new InputStreamFactory() {
                @Override
                public InputStream getInputStream() throws IOException {
                    return content;
                }
            };
            
            if (lobType.isAssignableFrom(SQLXML.class)) {
                value = new SQLXMLImpl(isf);    
            } else if (lobType.isAssignableFrom(ClobType.class)) {
                value = new ClobImpl(isf, -1);
            } else if (lobType.isAssignableFrom(BlobType.class)) {
                value = new BlobImpl(isf);
            } else {
                this.exceptions.add(new TeiidException(ODataPlugin.Util.gs(ODataPlugin.Event.TEIID16031, column.getName())));
            }
            this.params.add(new SQLParameter(value, sqlType));
        }
        update.setCriteria(this.context.getCriteria());
        return update;
    }
    
    public Delete delete() {
        Delete delete = new Delete();
        delete.setGroup(this.context.getGroupSymbol());
        delete.setCriteria(this.context.getCriteria());
        return delete;
    }    
    
    @Override
    public void visit(UriInfoService info) {
        this.exceptions.add(new TeiidException("UriInfoService NotSupported"));
    }

    @Override
    public void visit(UriInfoAll info) {
        this.exceptions.add(new TeiidException("UriInfoAll NotSupported"));
    }

    @Override
    public void visit(UriInfoBatch info) {
        this.exceptions.add(new TeiidException("UriInfoBatch NotSupported"));
    }

    @Override
    public void visit(UriInfoCrossjoin info) {
        for (String name:info.getEntitySetNames()) {
            EdmEntitySet entitySet = this.serviceMetadata.getEdm().getEntityContainer().getEntitySet(name);
            EdmEntityType entityType = entitySet.getEntityType();
            CrossJoinNode resource = null;
            try {
                boolean hasExpand = hasExpand(entitySet.getName(), info.getExpandOption());
                resource = CrossJoinNode.buildCrossJoin(entityType, null,
                        this.metadata, this.odata, this.nameGenerator, this.aliasedGroups,
                        getUriInfo(), this.parseService, hasExpand);
                resource.addAllColumns(!hasExpand);
                
                if (this.context == null) {
                    this.context = resource;                    
                    this.orderBy = this.context.addDefaultOrderBy();                    
                }
                else {
                    this.context.addSibiling(resource);
                    OrderBy orderby = resource.addDefaultOrderBy();
                    int index = orderby.getVariableCount();
                    for (int i = 0; i < index; i++) {
                        this.orderBy.addVariable(orderby.getVariable(i));
                    }
                }
            } catch (TeiidException e) {
                this.exceptions.add(e);
            }
        }
        super.visit(info);

        // the expand behavior is handled above with selection of the columns 
        this.expandOption = null;
    }
    
    private boolean hasExpand(String name, ExpandOption expandOption) {
        if (expandOption == null) {
            return false;
        }
        for (ExpandItem ei:expandOption.getExpandItems()) {
            String expand = ((UriResourceEntitySetImpl)ei.getResourcePath().getUriResourceParts().get(0)).getEntitySet().getName();
            if (expand.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void visit(UriInfoMetadata info) {
        this.exceptions.add(new TeiidException("UriInfoMetadata NotSupported"));
    }

    @Override
    public void visit(ExpandOption option) {
        this.expandOption = option;
    }

    @Override
    public void visit(FormatOption info) {
    }

    @Override
    public void visit(UriInfoEntityId info) {
        
        try {
            visit(buildUriInfo(new URI(info.getIdOption().getValue()), this.baseURI, this.serviceMetadata, this.odata));
        } catch (UriParserException e) {
            this.exceptions.add(new TeiidException(e));
        } catch (URISyntaxException e) {
            this.exceptions.add(new TeiidException(e));
        } catch (UriValidationException e) {
            this.exceptions.add(new TeiidException(e));
        }
        
        visit(info.getSelectOption());

        if (info.getExpandOption() != null) {
            visit(info.getExpandOption());
        }
        if (info.getFormatOption() != null) {
            visit(info.getFormatOption());
        }
    }

    static UriInfo buildUriInfo(URI uri, String baseUri,
            ServiceMetadata serviceMetadata, OData odata) throws URISyntaxException,
            UriParserException, UriValidationException {        
        URI servicePath = new URI(baseUri);
        String path = servicePath.getPath();
        
        String rawPath = uri.getPath();
        int e = rawPath.indexOf(path);
        if (-1 == e) {
          rawPath = uri.getPath();
        } else {
          rawPath = rawPath.substring(e+path.length());
        }
        return new Parser(serviceMetadata.getEdm(), odata).parseUri(rawPath, uri.getQuery(), null);           
    }
    
    @Override
    public void visit(UriResourceCount option) {
        if (option!= null) {
            this.countQuery = true;
        }
    }

    @Override
    public void visit(UriResourceRef info) {
        this.reference = true;
    }

    @Override
    public void visit(UriResourceRoot info) {
        this.exceptions.add(new TeiidException("UriResourceRoot NotSupported"));
    }

    @Override
    public void visit(UriResourceValue info) {
    }

    @Override
    public void visit(UriResourceAction info) {
        visitOperation(info.getAction());      
    }

    @Override
    public void visit(UriResourceFunction info) {
        visitOperation(info.getFunction());                
    }

    private void visitOperation(EdmOperation operation) {
        try {            
            ProcedureSQLBuilder builder = new ProcedureSQLBuilder(
                    this.metadata, operation, this.parameters,
                    this.params);
            ProcedureReturn pp = builder.getReturn();
            if (!pp.hasResultSet()) {
                NoDocumentNode ndn = new NoDocumentNode();
                ndn.setProcedureReturn(pp);
                ndn.setQuery(builder.buildProcedureSQL());
                this.context = ndn;
            } else {
                ComplexDocumentNode cdn = ComplexDocumentNode.buildComplexDocumentNode(
                        operation, this.metadata,
                        this.odata, this.nameGenerator, this.aliasedGroups,
                        getUriInfo(), this.parseService);
                cdn.setProcedureReturn(pp);
                this.context = cdn;
            }
        } catch (TeiidProcessingException e) {
            this.exceptions.add(e);
        }
    }
    
    @Override
    public void visit(UriResourceIt info) {
        this.exceptions.add(new TeiidException("UriResourceIt NotSupported"));
    }

    @Override
    public void visit(UriResourceLambdaAll info) {
        this.exceptions.add(new TeiidException("UriResourceLambdaAll NotSupported"));
    }

    @Override
    public void visit(UriResourceLambdaAny info) {
        this.exceptions.add(new TeiidException("UriResourceLambdaAll NotSupported"));
    }

    @Override
    public void visit(UriResourceLambdaVariable info) {
        this.exceptions.add(new TeiidException("UriResourceLambdaVariable NotSupported"));
    }

    @Override
    public void visit(UriResourceSingleton info) {
        this.exceptions.add(new TeiidException("UriResourceSingleton NotSupported"));
    }

    @Override
    public void visit(UriResourceComplexProperty info) {
        this.exceptions.add(new TeiidException("UriResourceComplexProperty NotSupported"));
    }

    public void setOperationParameterValueProvider(
            OperationParameterValueProvider parameters) {
        this.parameters = parameters;
    }
}
