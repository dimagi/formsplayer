package org.commcare.formplayer.tests;

import org.javarosa.core.model.condition.EvaluationContext;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.commcare.formplayer.sandbox.UserSqlSandbox;
import org.commcare.formplayer.utils.TestContext;
import org.commcare.formplayer.utils.TestStorageUtils;

import static org.commcare.formplayer.utils.DbTestUtils.evaluate;

/**
 * @author wspride
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = TestContext.class)
public class CaseDbOptimizationsTest extends BaseTestClass {

    @Override
    public void setUp() throws Exception {
        super.setUp();
        configureRestoreFactory("synctestdomain", "synctestusername");
    }

    @Override
    protected String getMockRestoreFileName() {
        return "restores/dbtests/case_test_db_optimizations.xml";
    }

    /**
     * Tests for basic common case database queries
     */
    @Test
    public void testDbOptimizations() throws Exception {
        syncDb();
        UserSqlSandbox sandbox = getRestoreSandbox();
        EvaluationContext ec = TestStorageUtils.getEvaluationContextWithoutSession(sandbox);
        ec.setDebugModeOn();
        evaluate("sort(join(' ',instance('casedb')/casedb/case[index/parent = 'test_case_parent']/@case_id))", "child_one child_three child_two", ec);
        evaluate("join(' ',instance('casedb')/casedb/case[index/parent = 'test_case_parent'][@case_id = 'child_two']/@case_id)", "child_two", ec);
        evaluate("sort(join(' ',instance('casedb')/casedb/case[index/parent = 'test_case_parent'][@case_id != 'child_two']/@case_id))", "child_one child_three", ec);

        evaluate("sort(join(' ',instance('casedb')/casedb/case[selected('test_case_parent', index/parent)]/@case_id))", "child_one child_three child_two", ec);
        evaluate("sort(join(' ',instance('casedb')/casedb/case[selected('test_case_parent test_case_parent_2', index/parent)]/@case_id))", "child_one child_three child_two", ec);
        evaluate("sort(join(' ',instance('casedb')/casedb/case[selected('test_case_parent_2 test_case_parent', index/parent)]/@case_id))", "child_one child_three child_two", ec);
        evaluate("join(' ',instance('casedb')/casedb/case[selected('test_case_parent_2 test_case_parent_3', index/parent)]/@case_id)", "", ec);
        evaluate("join(' ',instance('casedb')/casedb/case[selected('', index/parent)]/@case_id)", "", ec);
    }
}
