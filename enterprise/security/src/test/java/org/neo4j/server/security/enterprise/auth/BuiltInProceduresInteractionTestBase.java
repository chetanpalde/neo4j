/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.security.enterprise.auth;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.procedure.TerminationGuard;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.enterprise.builtinprocs.QueryId;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Procedure;
import org.neo4j.test.Barrier;
import org.neo4j.test.DoubleLatch;
import org.neo4j.test.rule.concurrent.ThreadingRule;

import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertFalse;
import static org.neo4j.graphdb.security.AuthorizationViolationException.PERMISSION_DENIED;
import static org.neo4j.helpers.collection.Iterables.single;
import static org.neo4j.helpers.collection.MapUtil.map;
import static org.neo4j.test.matchers.CommonMatchers.matchesOneToOneInAnyOrder;

public abstract class BuiltInProceduresInteractionTestBase<S> extends ProcedureInteractionTestBase<S>
{
    @Rule
    public final ThreadingRule threading = new ThreadingRule();

    /*
    This surface is hidden in 3.1, to possibly be completely removed or reworked later
    ==================================================================================
     */
    //---------- list running transactions -----------

    //@Test
    public void shouldListSelfTransaction()
    {
        assertSuccess( adminSubject, "CALL dbms.listTransactions()",
                r -> assertKeyIsMap( r, "username", "activeTransactions", map( "adminSubject", "1" ) ) );
    }

    //@Test
    public void shouldNotListTransactionsIfNotAdmin()
    {
        assertFail( noneSubject, "CALL dbms.listTransactions()", PERMISSION_DENIED );
        assertFail( readSubject, "CALL dbms.listTransactions()", PERMISSION_DENIED );
        assertFail( writeSubject, "CALL dbms.listTransactions()", PERMISSION_DENIED );
        assertFail( schemaSubject, "CALL dbms.listTransactions()", PERMISSION_DENIED );
    }

    //@Test
    public void shouldListTransactions() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3 );
        ThreadedTransaction<S> write1 = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> write2 = new ThreadedTransaction<>( neo, latch );

        String q1 = write1.executeCreateNode( threading, writeSubject );
        String q2 = write2.executeCreateNode( threading, writeSubject );
        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, "CALL dbms.listTransactions()",
                r -> assertKeyIsMap( r, "username", "activeTransactions",
                        map( "adminSubject", "1", "writeSubject", "2" ) ) );

        latch.finishAndWaitForAllToFinish();

        write1.closeAndAssertSuccess();
        write2.closeAndAssertSuccess();
    }

    //@Test
    public void shouldListRestrictedTransaction()
    {
        final DoubleLatch doubleLatch = new DoubleLatch( 2 );

        ClassWithProcedures.setTestLatch( new ClassWithProcedures.LatchedRunnables( doubleLatch, () -> {}, () -> {} ) );

        new Thread( () -> assertEmpty( writeSubject, "CALL test.waitForLatch()" ) ).start();
        doubleLatch.startAndWaitForAllToStart();
        try
        {
            assertSuccess( adminSubject, "CALL dbms.listTransactions()",
                    r -> assertKeyIsMap( r, "username", "activeTransactions",
                            map( "adminSubject", "1", "writeSubject", "1" ) ) );
        }
        finally
        {
            doubleLatch.finishAndWaitForAllToFinish();
        }
    }

    /*
    ==================================================================================
     */

    //---------- list running queries -----------

    @Test
    public void shouldListAllQueryIncludingMetaData() throws Throwable
    {
        String setMetaDataQuery = "CALL dbms.setTXMetaData( { realUser: 'MyMan' } )";
        String matchQuery = "MATCH (n) RETURN n";
        String listQueriesQuery = "CALL dbms.listQueries()";

        DoubleLatch latch = new DoubleLatch( 2 );
        OffsetDateTime startTime = OffsetDateTime.now();

        ThreadedTransaction<S> tx = new ThreadedTransaction<>( neo, latch );
        tx.execute( threading, writeSubject, setMetaDataQuery, matchQuery );

        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, listQueriesQuery, r ->
        {
            Set<Map<String,Object>> maps = r.stream().collect( Collectors.toSet() );

            Matcher<Map<String,Object>> thisQuery = listedQueryOfInteractionLevel( startTime, "adminSubject", listQueriesQuery );
            Matcher<Map<String,Object>> matchQueryMatcher =
                    listedQueryWithMetaData( startTime, "writeSubject", matchQuery, map( "realUser", "MyMan") );

            assertThat( maps, matchesOneToOneInAnyOrder( thisQuery, matchQueryMatcher ) );
        } );

        latch.finishAndWaitForAllToFinish();
        tx.closeAndAssertSuccess();
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldListAllQueriesWhenRunningAsAdmin() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3, true );
        OffsetDateTime startTime = OffsetDateTime.now();

        ThreadedTransaction<S> read1 = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> read2 = new ThreadedTransaction<>( neo, latch );

        String q1 = read1.execute( threading, readSubject, "UNWIND [1,2,3] AS x RETURN x" );
        String q2 = read2.execute( threading, writeSubject, "UNWIND [4,5,6] AS y RETURN y" );
        latch.startAndWaitForAllToStart();

        String query = "CALL dbms.listQueries()";
        assertSuccess( adminSubject, query, r ->
        {
            Set<Map<String,Object>> maps = r.stream().collect( Collectors.toSet() );

            Matcher<Map<String,Object>> thisQuery = listedQueryOfInteractionLevel( startTime, "adminSubject", query );
            Matcher<Map<String,Object>> matcher1 = listedQuery( startTime, "readSubject", q1 );
            Matcher<Map<String,Object>> matcher2 = listedQuery( startTime, "writeSubject", q2 );

            assertThat( maps, matchesOneToOneInAnyOrder( matcher1, matcher2, thisQuery ) );
        } );

        latch.finishAndWaitForAllToFinish();

        read1.closeAndAssertSuccess();
        read2.closeAndAssertSuccess();
    }

    @Test
    public void shouldOnlyListOwnQueriesWhenNotRunningAsAdmin() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3, true );
        OffsetDateTime startTime = OffsetDateTime.now();
        ThreadedTransaction<S> read1 = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> read2 = new ThreadedTransaction<>( neo, latch );

        String q1 = read1.execute( threading, readSubject, "UNWIND [1,2,3] AS x RETURN x" );
        String ignored = read2.execute( threading, writeSubject, "UNWIND [4,5,6] AS y RETURN y" );
        latch.startAndWaitForAllToStart();

        String query = "CALL dbms.listQueries()";
        assertSuccess( readSubject, query, r ->
        {
            Set<Map<String,Object>> maps = r.stream().collect( Collectors.toSet() );

            Matcher<Map<String,Object>> thisQuery = listedQuery( startTime, "readSubject", query );
            Matcher<Map<String,Object>> queryMatcher = listedQuery( startTime, "readSubject", q1 );

            assertThat( maps, matchesOneToOneInAnyOrder( queryMatcher, thisQuery ) );
        } );

        latch.finishAndWaitForAllToFinish();

        read1.closeAndAssertSuccess();
        read2.closeAndAssertSuccess();
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldListQueriesEvenIfUsingPeriodicCommit() throws Throwable
    {
        for ( int i = 8; i <= 11; i++ )
        {
            // Spawns a throttled HTTP server, runs a PERIODIC COMMIT that fetches data from this server,
            // and checks that the query is visible when using listQueries()

            // Given
            final DoubleLatch latch = new DoubleLatch( 3, true );
            final Barrier.Control barrier = new Barrier.Control();

            // Serve CSV via local web server, let Jetty find a random port for us
            Server server = createHttpServer( latch, barrier, i, 50-i );
            server.start();
            int localPort = getLocalPort( server );

            OffsetDateTime startTime = OffsetDateTime.now();

            // When
            ThreadedTransaction<S> write = new ThreadedTransaction<>( neo, latch );

            try
            {
                String writeQuery = write.executeEarly( threading, writeSubject, KernelTransaction.Type.implicit,
                        format( "USING PERIODIC COMMIT 10 LOAD CSV FROM 'http://localhost:%d' AS line ", localPort ) +
                                "CREATE (n:A {id: line[0], square: line[1]}) " + "RETURN count(*)" );
                latch.startAndWaitForAllToStart();

                // Then
                String query = "CALL dbms.listQueries()";
                assertSuccess( adminSubject, query, r ->
                {
                    Set<Map<String,Object>> maps = r.stream().collect( Collectors.toSet() );

                    Matcher<Map<String,Object>> thisMatcher = listedQuery( startTime, "adminSubject", query );
                    Matcher<Map<String,Object>> writeMatcher = listedQuery( startTime, "writeSubject", writeQuery );

                    assertThat( maps, hasItem( thisMatcher ) );
                    assertThat( maps, hasItem( writeMatcher ) );
                } );
            }
            finally
            {
                // When
                barrier.release();
                latch.finishAndWaitForAllToFinish();
                server.stop();

                // Then
                write.closeAndAssertSuccess();
            }
        }
    }

    //---------- terminate query -----------

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldKillQueryAsAdmin() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3 );
        ThreadedTransaction<S> read1 = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> read2 = new ThreadedTransaction<>( neo, latch );
        String q1 = read1.execute( threading, readSubject, "UNWIND [1,2,3] AS x RETURN x" );
        String q2 = read2.execute( threading, readSubject, "UNWIND [4,5,6] AS y RETURN y" );
        latch.startAndWaitForAllToStart();

        String id1 = extractQueryId( q1 );

        assertSuccess(
                adminSubject,
                "CALL dbms.killQuery('" + id1 + "') YIELD username " +
                "RETURN count(username) AS count, username", r ->
                {
                    List<Map<String,Object>> actual = r.stream().collect( toList() );
                    Matcher<Map<String,Object>> mapMatcher = allOf(
                            (Matcher) hasEntry( equalTo( "count" ), anyOf( equalTo( 1 ), equalTo( 1L ) ) ),
                            (Matcher) hasEntry( equalTo( "username" ), equalTo( "readSubject" ) )
                    );
                    assertThat( actual, matchesOneToOneInAnyOrder( mapMatcher ) );
                }
            );

        latch.finishAndWaitForAllToFinish();
        read1.closeAndAssertTransactionTermination();
        read2.closeAndAssertSuccess();

        assertEmpty(
            adminSubject,
            "CALL dbms.listQueries() YIELD query WITH * WHERE NOT query CONTAINS 'listQueries' RETURN *" );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldKillQueryAsUser() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3 );
        ThreadedTransaction<S> read = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> write = new ThreadedTransaction<>( neo, latch );
        String q1 = read.execute( threading, readSubject, "UNWIND [1,2,3] AS x RETURN x" );
        String q2 = write.execute( threading, writeSubject, "UNWIND [4,5,6] AS y RETURN y" );
        latch.startAndWaitForAllToStart();

        String id1 = extractQueryId( q1 );

        assertSuccess(
                readSubject,
                "CALL dbms.killQuery('" + id1 + "') YIELD username " +
                "RETURN count(username) AS count, username", r ->
                {
                    List<Map<String,Object>> actual = r.stream().collect( toList() );
                    Matcher<Map<String,Object>> mapMatcher = allOf(
                            (Matcher) hasEntry( equalTo( "count" ), anyOf( equalTo( 1 ), equalTo( 1L ) ) ),
                            (Matcher) hasEntry( equalTo( "username" ), equalTo( "readSubject" ) )
                    );
                    assertThat( actual, matchesOneToOneInAnyOrder( mapMatcher ) );
                }
            );

        latch.finishAndWaitForAllToFinish();
        read.closeAndAssertTransactionTermination();
        write.closeAndAssertSuccess();

        assertEmpty(
            adminSubject,
            "CALL dbms.listQueries() YIELD query WITH * WHERE NOT query CONTAINS 'listQueries' RETURN *" );
    }

    @Test
    public void shouldSelfKillQuery() throws Throwable
    {
        String result = neo.executeQuery(
            readSubject,
            "WITH 'Hello' AS marker CALL dbms.listQueries() YIELD queryId AS id, query " +
            "WITH * WHERE query CONTAINS 'Hello' CALL dbms.killQuery(id) YIELD username " +
            "RETURN count(username) AS count, username",
            Collections.emptyMap(),
            r -> {}
        );

        assertThat( result, containsString( "Explicitly terminated by the user." ) );

        assertEmpty(
            adminSubject,
            "CALL dbms.listQueries() YIELD query WITH * WHERE NOT query CONTAINS 'listQueries' RETURN *" );
    }

    @Test
    public void shouldFailToTerminateOtherUsersQuery() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3, true );
        ThreadedTransaction<S> read = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> write = new ThreadedTransaction<>( neo, latch );
        String q1 = read.execute( threading, readSubject, "UNWIND [1,2,3] AS x RETURN x" );
        write.execute( threading, writeSubject, "UNWIND [4,5,6] AS y RETURN y" );
        latch.startAndWaitForAllToStart();

        try
        {
            String id1 = extractQueryId( q1 );
            assertFail(
                writeSubject,
                "CALL dbms.killQuery('" + id1 + "') YIELD username RETURN *",
                PERMISSION_DENIED
            );
            latch.finishAndWaitForAllToFinish();
            read.closeAndAssertSuccess();
            write.closeAndAssertSuccess();
        }
        catch (Throwable t)
        {
            latch.finishAndWaitForAllToFinish();
            throw t;
        }

        assertEmpty(
            adminSubject,
            "CALL dbms.listQueries() YIELD query WITH * WHERE NOT query CONTAINS 'listQueries' RETURN *" );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldTerminateQueriesEvenIfUsingPeriodicCommit() throws Throwable
    {
        for ( int i = 8; i <= 11; i++ )
        {
            // Spawns a throttled HTTP server, runs a PERIODIC COMMIT that fetches data from this server,
            // and checks that the query is visible when using listQueries()

            // Given
            final DoubleLatch latch = new DoubleLatch( 3, true );
            final Barrier.Control barrier = new Barrier.Control();

            // Serve CSV via local web server, let Jetty find a random port for us
            Server server = createHttpServer( latch, barrier, i, 50-i );
            server.start();
            int localPort = getLocalPort( server );

            // When
            ThreadedTransaction<S> write = new ThreadedTransaction<>( neo, latch );

            try
            {
                String writeQuery = write.executeEarly( threading, writeSubject, KernelTransaction.Type.implicit,
                        format( "USING PERIODIC COMMIT 10 LOAD CSV FROM 'http://localhost:%d' AS line ", localPort ) +
                                "CREATE (n:A {id: line[0], square: line[1]}) RETURN count(*)" );
                latch.startAndWaitForAllToStart();

                // Then
                String writeQueryId = extractQueryId( writeQuery );

                assertSuccess(
                        adminSubject,
                        "CALL dbms.killQuery('" + writeQueryId + "') YIELD username " +
                        "RETURN count(username) AS count, username", r ->
                        {
                            List<Map<String,Object>> actual = r.stream().collect( toList() );
                            Matcher<Map<String,Object>> mapMatcher = allOf(
                                    (Matcher) hasEntry( equalTo( "count" ), anyOf( equalTo( 1 ), equalTo( 1L ) ) ),
                                    (Matcher) hasEntry( equalTo( "username" ), equalTo( "writeSubject" ) )
                            );
                            assertThat( actual, matchesOneToOneInAnyOrder( mapMatcher ) );
                        }
                );
            }
            finally
            {
                // When
                barrier.release();
                latch.finishAndWaitForAllToFinish();
                server.stop();

                // Then
                write.closeAndAssertTransactionTermination();
            }
        }
    }

    @Test
    public void shouldKillMultipleUserQueries() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 5 );
        ThreadedTransaction<S> read1 = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> read2 = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> read3 = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> write = new ThreadedTransaction<>( neo, latch );
        String q1 = read1.execute( threading, readSubject, "UNWIND [1,2,3] AS x RETURN x" );
        String q2 = read2.execute( threading, readSubject, "UNWIND [4,5,6] AS y RETURN y" );
        read3.execute( threading, readSubject, "UNWIND [7,8,9] AS z RETURN z" );
        write.execute( threading, writeSubject, "UNWIND [11,12,13] AS q RETURN q" );
        latch.startAndWaitForAllToStart();

        String id1 = extractQueryId( q1 );
        String id2 = extractQueryId( q2 );

        String idParam = "['" + id1 + "', '" + id2 + "']";

        assertSuccess(
                adminSubject,
                "CALL dbms.killQueries(" + idParam + ") YIELD username " +
                "RETURN count(username) AS count, username", r ->
                {
                    List<Map<String,Object>> actual = r.stream().collect( toList() );
                    Matcher<Map<String,Object>> mapMatcher = allOf(
                            (Matcher) hasEntry( equalTo( "count" ), anyOf( equalTo( 2 ), equalTo( 2L ) ) ),
                            (Matcher) hasEntry( equalTo( "username" ), equalTo( "readSubject" ) )
                    );
                    assertThat( actual, matchesOneToOneInAnyOrder( mapMatcher ) );
                }
        );

        latch.finishAndWaitForAllToFinish();
        read1.closeAndAssertTransactionTermination();
        read2.closeAndAssertTransactionTermination();
        read3.closeAndAssertSuccess();
        write.closeAndAssertSuccess();

        assertEmpty(
            adminSubject,
            "CALL dbms.listQueries() YIELD query WITH * WHERE NOT query CONTAINS 'listQueries' RETURN *" );
    }

    String extractQueryId( String writeQuery )
    {
        return single( collectSuccessResult( adminSubject, "CALL dbms.listQueries()" )
                .stream()
                .filter( m -> m.get( "query" ).equals( writeQuery ) )
                .collect( toList() ) )
                .get( "queryId" )
                .toString();
    }

    //---------- set tx meta data -----------

    @Test
    public void shouldHaveSetTXMetaDataProcedure() throws Throwable
    {
        assertEmpty( writeSubject, "CALL dbms.setTXMetaData( { aKey: 'aValue' } )" );
    }

    @Test
    public void shouldFailOnTooLargeTXMetaData() throws Throwable
    {
        ArrayList<String> list = new ArrayList<>();
        for ( int i = 0; i < 200; i++ )
        {
            list.add( format( "key%d: '0123456789'", i ) );
        }
        String longMetaDataMap = "{" + String.join( ", ", list ) + "}";
        assertFail( writeSubject, "CALL dbms.setTXMetaData( " + longMetaDataMap + " )",
            "Invalid transaction meta-data, expected the total number of chars for keys and values to be " +
            "less than 2048, got 3090" );
    }

    //---------- procedure guard -----------

    @Test
    public void shouldTerminateLongRunningProcedureThatChecksTheGuardRegularlyIfKilled() throws Throwable
    {
        neo
            .getLocalGraph()
            .getDependencyResolver()
            .resolveDependency( Procedures.class )
            .registerProcedure( NeverEndingProcedure.class );

        final DoubleLatch latch = new DoubleLatch( 2 );
        NeverEndingProcedure.setTestLatch( new ClassWithProcedures.LatchedRunnables( latch, () -> {}, () -> {} ) );

        String loopQuery = "CALL test.loop";

        new Thread( () -> assertFail( readSubject, loopQuery, "Explicitly terminated by the user." ) )
                .start();
        latch.startAndWaitForAllToStart();

        try
        {
            String loopId = extractQueryId( loopQuery );

            assertSuccess(
                    adminSubject,
                    "CALL dbms.killQuery('" + loopId + "') YIELD username " +
                    "RETURN count(username) AS count, username", r ->
                    {
                        List<Map<String,Object>> actual = r.stream().collect( toList() );
                        Matcher<Map<String,Object>> mapMatcher = allOf(
                                (Matcher) hasEntry( equalTo( "count" ), anyOf( equalTo( 1 ), equalTo( 1L ) ) ),
                                (Matcher) hasEntry( equalTo( "username" ), equalTo( "readSubject" ) )
                        );
                        assertThat( actual, matchesOneToOneInAnyOrder( mapMatcher ) );
                    }
            );
        }
        finally
        {
            latch.finishAndWaitForAllToFinish();
        }

        assertEmpty(
                adminSubject,
                "CALL dbms.listQueries() YIELD query WITH * WHERE NOT query CONTAINS 'listQueries' RETURN *" );
    }

    @Test
    public void shouldTerminateLongRunningProcedureThatChecksTheGuardRegularlyOnTimeout() throws Throwable
    {

        neo.tearDown();
        Map<String, String> config = new HashMap<>();
        config.put( GraphDatabaseSettings.transaction_timeout.name(), "3s" );
        neo = setUpNeoServer( config );
        reSetUp();

        neo
           .getLocalGraph()
           .getDependencyResolver()
           .resolveDependency( Procedures.class )
           .registerProcedure( NeverEndingProcedure.class );

        assertFail(
            adminSubject,
            "CALL test.loop",
            "Transaction guard check failed"
       );

        Result result = neo
            .getLocalGraph()
            .execute( "CALL dbms.listQueries() YIELD query WITH * WHERE NOT query CONTAINS 'listQueries' RETURN *" );

        assertFalse( result.hasNext() );
        result.close();
    }

    /*
    This surface is hidden in 3.1, to possibly be completely removed or reworked later
    ==================================================================================
     */

    //---------- terminate transactions for user -----------

    //@Test
    public void shouldTerminateTransactionForUser() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 2 );
        ThreadedTransaction<S> write = new ThreadedTransaction<>( neo, latch );
        write.executeCreateNode( threading, writeSubject );
        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, "CALL dbms.terminateTransactionsForUser( 'writeSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "writeSubject", "1" ) ) );

        assertSuccess( adminSubject, "CALL dbms.listTransactions()",
                r -> assertKeyIsMap( r, "username", "activeTransactions", map( "adminSubject", "1" ) ) );

        latch.finishAndWaitForAllToFinish();

        write.closeAndAssertTransactionTermination();

        assertEmpty( adminSubject, "MATCH (n:Test) RETURN n.name AS name" );
    }

    //@Test
    public void shouldTerminateOnlyGivenUsersTransaction() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3 );
        ThreadedTransaction<S> schema = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> write = new ThreadedTransaction<>( neo, latch );

        schema.executeCreateNode( threading, schemaSubject );
        write.executeCreateNode( threading, writeSubject );
        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, "CALL dbms.terminateTransactionsForUser( 'schemaSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "schemaSubject", "1" ) ) );

        assertSuccess( adminSubject, "CALL dbms.listTransactions()",
                r ->  assertKeyIsMap( r, "username", "activeTransactions",
                        map( "adminSubject", "1", "writeSubject", "1" ) ) );

        latch.finishAndWaitForAllToFinish();

        schema.closeAndAssertTransactionTermination();
        write.closeAndAssertSuccess();

        assertSuccess( adminSubject, "MATCH (n:Test) RETURN n.name AS name",
                r -> assertKeyIs( r, "name", "writeSubject-node" ) );
    }

    //@Test
    public void shouldTerminateAllTransactionsForGivenUser() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 3 );
        ThreadedTransaction<S> schema1 = new ThreadedTransaction<>( neo, latch );
        ThreadedTransaction<S> schema2 = new ThreadedTransaction<>( neo, latch );

        schema1.executeCreateNode( threading, schemaSubject );
        schema2.executeCreateNode( threading, schemaSubject );
        latch.startAndWaitForAllToStart();

        assertSuccess( adminSubject, "CALL dbms.terminateTransactionsForUser( 'schemaSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "schemaSubject", "2" ) ) );

        assertSuccess( adminSubject, "CALL dbms.listTransactions()",
                r ->  assertKeyIsMap( r, "username", "activeTransactions", map( "adminSubject", "1" ) ) );

        latch.finishAndWaitForAllToFinish();

        schema1.closeAndAssertTransactionTermination();
        schema2.closeAndAssertTransactionTermination();

        assertEmpty( adminSubject, "MATCH (n:Test) RETURN n.name AS name" );
    }

    //@Test
    public void shouldNotTerminateTerminationTransaction() throws InterruptedException, ExecutionException
    {
        assertSuccess( adminSubject, "CALL dbms.terminateTransactionsForUser( 'adminSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "adminSubject", "0" ) ) );
        assertSuccess( readSubject, "CALL dbms.terminateTransactionsForUser( 'readSubject' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "readSubject", "0" ) ) );
    }

    //@Test
    public void shouldTerminateSelfTransactionsExceptTerminationTransactionIfAdmin() throws Throwable
    {
        shouldTerminateSelfTransactionsExceptTerminationTransaction( adminSubject );
    }

    //@Test
    public void shouldTerminateSelfTransactionsExceptTerminationTransactionIfNotAdmin() throws Throwable
    {
        shouldTerminateSelfTransactionsExceptTerminationTransaction( writeSubject );
    }

    private void shouldTerminateSelfTransactionsExceptTerminationTransaction( S subject ) throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 2 );
        ThreadedTransaction<S> create = new ThreadedTransaction<>( neo, latch );
        create.executeCreateNode( threading, subject );

        latch.startAndWaitForAllToStart();

        String subjectName = neo.nameOf( subject );
        assertSuccess( subject, "CALL dbms.terminateTransactionsForUser( '" + subjectName + "' )",
                r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( subjectName, "1" ) ) );

        latch.finishAndWaitForAllToFinish();

        create.closeAndAssertTransactionTermination();

        assertEmpty( adminSubject, "MATCH (n:Test) RETURN n.name AS name" );
    }

    //@Test
    public void shouldNotTerminateTransactionsIfNonExistentUser() throws InterruptedException, ExecutionException
    {
        assertFail( adminSubject, "CALL dbms.terminateTransactionsForUser( 'Petra' )", "User 'Petra' does not exist" );
        assertFail( adminSubject, "CALL dbms.terminateTransactionsForUser( '' )", "User '' does not exist" );
    }

    //@Test
    public void shouldNotTerminateTransactionsIfNotAdmin() throws Throwable
    {
        DoubleLatch latch = new DoubleLatch( 2 );
        ThreadedTransaction<S> write = new ThreadedTransaction<>( neo, latch );
        write.executeCreateNode( threading, writeSubject );
        latch.startAndWaitForAllToStart();

        assertFail( noneSubject, "CALL dbms.terminateTransactionsForUser( 'writeSubject' )", PERMISSION_DENIED );
        assertFail( pwdSubject, "CALL dbms.terminateTransactionsForUser( 'writeSubject' )", CHANGE_PWD_ERR_MSG );
        assertFail( readSubject, "CALL dbms.terminateTransactionsForUser( 'writeSubject' )", PERMISSION_DENIED );
        assertFail( schemaSubject, "CALL dbms.terminateTransactionsForUser( 'writeSubject' )", PERMISSION_DENIED );

        assertSuccess( adminSubject, "CALL dbms.listTransactions()",
                r -> assertKeyIs( r, "username", "adminSubject", "writeSubject" ) );

        latch.finishAndWaitForAllToFinish();

        write.closeAndAssertSuccess();

        assertSuccess( adminSubject, "MATCH (n:Test) RETURN n.name AS name",
                r -> assertKeyIs( r, "name", "writeSubject-node" ) );
    }

    //@Test
    public void shouldTerminateRestrictedTransaction()
    {
        final DoubleLatch doubleLatch = new DoubleLatch( 2 );

        ClassWithProcedures.setTestLatch( new ClassWithProcedures.LatchedRunnables( doubleLatch, () -> {}, () -> {} ) );

        new Thread( () -> assertFail( writeSubject, "CALL test.waitForLatch()", "Explicitly terminated by the user." ) )
                .start();

        doubleLatch.startAndWaitForAllToStart();
        try
        {
            assertSuccess( adminSubject, "CALL dbms.terminateTransactionsForUser( 'writeSubject' )",
                    r -> assertKeyIsMap( r, "username", "transactionsTerminated", map( "writeSubject", "1" ) ) );
        }
        finally
        {
            doubleLatch.finishAndWaitForAllToFinish();
        }
    }

    /*
    ==================================================================================
     */

    //---------- jetty helpers for serving CSV files -----------

    private int getLocalPort( Server server )
    {
        return ((ServerConnector) (server.getConnectors()[0])).getLocalPort();

    }

    private Server createHttpServer(
            DoubleLatch outerLatch, Barrier.Control innerBarrier,
            int firstBatchSize, int otherBatchSize )
    {
        Server server = new Server( 0 );
        server.setHandler( new AbstractHandler()
        {
            @Override
            public void handle(
                    String target,
                    Request baseRequest,
                    HttpServletRequest request,
                    HttpServletResponse response
            ) throws IOException, ServletException
            {
                response.setContentType( "text/plain; charset=utf-8" );
                response.setStatus( HttpServletResponse.SC_OK );
                PrintWriter out = response.getWriter();

                writeBatch( out, firstBatchSize );
                out.flush();
                outerLatch.start();

                innerBarrier.reached();

                outerLatch.finish();
                writeBatch( out, otherBatchSize );
                baseRequest.setHandled(true);
            }

            private void writeBatch( PrintWriter out, int batchSize )
            {
                for ( int i = 0; i < batchSize; i++ )
                {
                    out.write( format( "%d %d\n", i, i*i ) );
                    i++;
                }
            }
        } );
        return server;
    }

    //---------- matchers-----------

    private Matcher<Map<String,Object>> listedQuery( OffsetDateTime startTime, String username, String query )
    {
        return allOf(
                hasQuery( query ),
                hasUsername( username ),
                hasQueryId(),
                hasStartTimeAfter( startTime ),
                hasNoParameters()
        );
    }

    /**
     * Executes a query through the NeoInteractionLevel required
     */
    private Matcher<Map<String,Object>> listedQueryOfInteractionLevel( OffsetDateTime startTime, String username,
            String query )
    {
        return allOf(
                hasQuery( query ),
                hasUsername( username ),
                hasQueryId(),
                hasStartTimeAfter( startTime ),
                hasNoParameters(),
                hasConnectionDetails( neo.getConnectionDetails() )
        );
    }

    private Matcher<Map<String,Object>> listedQueryWithMetaData( OffsetDateTime startTime, String username,
            String query, Map<String,Object> metaData )
    {
        return allOf(
                hasQuery( query ),
                hasUsername( username ),
                hasQueryId(),
                hasStartTimeAfter( startTime ),
                hasNoParameters(),
                hasMetaData( metaData )
        );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasQuery( String query )
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "query" ), equalTo( query ) );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasUsername( String username )
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "username" ), equalTo( username ) );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasQueryId()
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry(
            equalTo( "queryId" ),
            allOf( (Matcher) isA( String.class ), (Matcher) containsString( QueryId.QUERY_ID_PREFIX ) )
        );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasStartTimeAfter( OffsetDateTime startTime )
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "startTime" ), new BaseMatcher<String>()
        {
            @Override
            public void describeTo( Description description )
            {
                description.appendText( "should be after " + startTime.toString() );
            }

            @Override
            public boolean matches( Object item )
            {
                OffsetDateTime otherTime =  OffsetDateTime.from( ISO_OFFSET_DATE_TIME.parse( item.toString() ) );
                return startTime.compareTo( otherTime ) <= 0;
            }
        } );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasNoParameters()
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "parameters" ), equalTo( Collections.emptyMap() ) );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasConnectionDetails( String expected )
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "connectionDetails" ),
                containsString( expected ) );
    }

    @SuppressWarnings( "unchecked" )
    private Matcher<Map<String, Object>> hasMetaData( Map<String,Object> expected )
    {
        return (Matcher<Map<String, Object>>) (Matcher) hasEntry( equalTo( "metaData" ),
                allOf(
                    expected.entrySet().stream().map(
                            entry -> hasEntry(
                                    equalTo( entry.getKey() ),
                                    equalTo( entry.getValue() )
                            )
                        ).collect( Collectors.toList() )
                ) );
    }

    @Override
    protected ThreadingRule threading()
    {
        return threading;
    }

    public static class NeverEndingProcedure
    {
        private static final AtomicReference<ClassWithProcedures.LatchedRunnables> testLatch = new AtomicReference<>();

        @Context
        public TerminationGuard guard;

        @Procedure(name = "test.loop")
        public void loop()
        {
            testLatch.get().doubleLatch.startAndWaitForAllToStart();
            try
            {
                while ( true )
                {
                    try
                    {
                        Thread.sleep( 100 );
                    }
                    catch ( InterruptedException e )
                    {
                        Thread.interrupted();
                    }
                    guard.check();
                }
            }
            finally
            {
                testLatch.get().doubleLatch.finish();
            }
        }

        static void setTestLatch( ClassWithProcedures.LatchedRunnables testLatch )
        {
            NeverEndingProcedure.testLatch.set( testLatch );
        }
    }
}
